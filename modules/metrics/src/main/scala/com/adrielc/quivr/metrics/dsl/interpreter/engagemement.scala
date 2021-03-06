package com.adrielc.quivr.metrics
package dsl
package interpreter

import cats.data.Kleisli
import cats.~>
import cats.implicits._
import com.adrielc.quivr.metrics.data.relevance.Relevance
import com.adrielc.quivr.metrics.dsl.engagement.{Judge, Labeler}
import matryoshka.implicits._


object engagemement {
  import engagement.JudgeF._
  import engagement.LabelerF._
  type FromEngs[E, A] = Kleisli[Option, Map[E, Int], A]

  object label {
    val labelerCompiler: Labeler ~> FromEngs[*, Double] = {
      new (engagement.Labeler ~> λ[α => Kleisli[Option, Map[α, Int], Double]]) {
        def apply[A](fa: engagement.Labeler[A]): Kleisli[Option, Map[A, Int], Double] =
          fa.cata[FromEngs[A, Double]] {
            case Value(value)     => Kleisli.pure(value)
            case Count(e)         => Kleisli(_.get(e).map(_.toDouble))
            case Sum(e1, e2)      => e1 |+| e2
            case Log(e1, base)    => e1.mapF(v => v.filter(_ >= 0).map(math.log1p(_) / math.log(base)))
            case Mult(e1, e2)     => combWith(e1, e2)(_ * _)
            case Div(num, den)    => combWith(num, den)(safeDiv(_, _).getOrElse(0.0))
            case i: As[A, FromEngs[A, Double]] => judge.judgeCompiler(i.i).flatMap(b => if(b) i.t else Kleisli(_ => none[Double]))
            case Or(e1, e2)       => e1 <+> e2
            case And(e1, e2)      => Kleisli(e => e1(e).flatMap(a => e2(e).map(b => a + b)))
            case eqv: Equiv[A, Double] @unchecked => for {
              a <- labelerCompiler(eqv.a)
              b <- labelerCompiler(eqv.b)
              o <- (if(eqv.eq(a, b)) Kleisli.pure(a) else Kleisli(_ => none[Double])): Kleisli[Option, Map[A, Int], Double]
            } yield o
          }
      }
    }

    val labelerToRelevanceCompiler: Labeler ~> FromEngs[*, Relevance] = {
      new (Labeler ~> FromEngs[*, Relevance]) {
        override def apply[A](fa: Labeler[A]): FromEngs[A, Relevance] =
          labelerCompiler(fa).map(Relevance.label)
      }
    }
  }


  object judge {

    val judgeCompiler: Judge ~> FromEngs[*, Boolean] = {
      new (engagement.Judge ~> FromEngs[*, Boolean]) {
        def apply[A](fa: engagement.Judge[A]): FromEngs[A, Boolean] =
          fa.cata[FromEngs[A, Boolean]] {
            case Or(e1, e2)       => combWith(e1, e2)(_ || _)
            case And(e1, e2)      => combWith(e1, e2)(_ && _)
            case eqv: Equiv[A, Boolean]  @unchecked => for {
              a <- orElseZeroCompiler(eqv.a)
              b <- orElseZeroCompiler(eqv.b)
            } yield eqv.eq(a, b)
          }
      }
    }

    val judgementCompilerToRelevance: Judge ~> FromEngs[*, Relevance] =
      new (engagement.Judge ~> FromEngs[*, Relevance]) {
        override def apply[A](fa: Judge[A]): FromEngs[A, Relevance] =
          judgeCompiler(fa).map(Relevance.judge)
      }

    private val orElseZeroCompiler: Labeler ~> FromEngs[*, Double] =
      λ[engagement.Labeler ~> FromEngs[*, Double]](fa => label.labelerCompiler(fa) <+> Kleisli.pure(0.0))
  }

  private def combWith[E, A](ka: FromEngs[E, A], kb: FromEngs[E, A])(f: (A, A) => A): FromEngs[E, A] =
    Kleisli(e => ka.run(e).foldMapK(a => kb.run(e).map(b => f(a, b))))
}
