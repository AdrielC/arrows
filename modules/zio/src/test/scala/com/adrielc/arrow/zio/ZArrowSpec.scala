package com.adrielc.arrow.zio

import com.adrielc.arrow.data.~~>
import com.adrielc.arrow.free.FreeA
import com.adrielc.arrow.free.FreeA._
import com.adrielc.arrow.zio.ZArrow.ZA
import org.scalatest.{FlatSpec, Matchers}
import zio.URIO
import zio.interop.catz._

class ZArrowSpec extends FlatSpec with Matchers {

  "ZArrow" should "not stack overflow" in {

    val add1 = ZArrow.lift((i: Int) => i + 1)

    var z = add1

    for (_ <- 1 until 10000) z = z.andThen(add1)

    val run = z.run(0)

    assert(zio.Runtime.default.unsafeRun(run) === 10000)
  }


  sealed trait Expr[A, B]
  case object Add10 extends Expr[Int, Int]

  val add5 = FreeA.lift((_: Int) + 5)

  val add10 = FreeA.liftK(Add10)

  val add15 = FreeA.lift((_: Int) + 15)


  val adder = (add5 >>> add10 >>> add15).loopN(1000)

  val zioA = adder.foldMap(new (Expr ~~> URIO) {
    def apply[A, B](fab: Expr[A, B]): URIO[A, B] = fab match {
      case Add10 => URIO.access(i => i + 10)
    }
  })


  val zArrow = adder.foldMap(new (Expr ~~> ZA) {
    def apply[A, B](fab: Expr[A, B]): ZA[A, B] = fab match {
      case Add10 => ZArrow.lift(i => i + 10)
    }
  })


  "zioA" should "be slower than ZArrow" in {

    val timed = for {

      it1 <- zioA.provide(0).timed

      it2 <- zArrow.run(0).timed

    } yield it1._1.toMillis - it2._1.toMillis

    val diff = zio.Runtime.default.unsafeRun(timed)

    println(diff)
  }
}
