package com.adrielc.arrow.free

import cats.{Eval, Monoid}
import cats.arrow.{Arrow, ArrowChoice}
import com.adrielc.arrow.{ArrowChoicePlus, ArrowChoiceZero, ArrowPlus, ArrowZero, ~>>, ~~>}
import cats.implicits._
import com.adrielc.arrow.data.ConstP

sealed trait FreeA[-Arr[f[_, _]] <: Arrow[f], +F[_, _], A, B] {
  self =>
  import FreeA._

  def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G]): G[A, B]

  final def analyze[M: Monoid](m: F ~>> M)(implicit A: Arr[ConstP[M, ?, ?]]): M =
    foldMap(new (F ~~> ConstP[M, ?, ?]) {
      def apply[C, D](f: F[C, D]): ConstP[M, C, D] = ConstP(m.apply(f))
    }).getConst

  /**
   * Fuses any pure functions if possible, otherwise wraps the arrows in [[AndThen]]
   */
  def andThen[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C]
  (fbc: FreeA[Ar, FF, B, C]): FreeA[Ar, FF, A, C] = self match {

    case Fn(f) => fbc match {
      case Fn(g) => Fn(f andThen g)
      case a: AndThen[Ar, FF, B, b, C] => AndThen(andThen(a.begin), a.end)
      case _ => AndThen(self, fbc)
    }

    case AndThen(begin, end) => AndThen(begin, end andThen fbc)

    case _ => AndThen(self, fbc)
  }

  /**
   * Merges pure functions if possible, otherwise wraps the arrows in [[Merge]]
   */
  final def merge[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C]
  (fac: FreeA[Ar, FF, A, C])
  : FreeA[Ar, FF, A, (B, C)] =
    (self, fac) match {
      case (Fn(f), Fn(g)) => fn(a => (f(a), g(a)))
      case _ => Merge(self, fac)
    }

  /** [[First]], equivalent to [[cats.arrow.Strong.first]] */
  final def first[C]
  : FreeA[Arr, F, (A, C), (B, C)] = First(self)

  /** [[Second]], equivalent to [[cats.arrow.Strong.second]] */
  final def second[C]
  : FreeA[Arr, F, (C, A), (C, B)] = Second(self)

  /** [[Left]] */
  final def left[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C](implicit L: LubC[Ar])
  : FreeA[L.Lub, FF, Either[A, C], Either[B, C]] = Left(self)

  /** [[Right]] */
  final def right[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C](implicit L: LubC[Ar])
  : FreeA[L.Lub, FF, Either[C, A], Either[C, B]] = Right(self)

  /** [[Choice]] */
  final def choice[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C]
  (fcb: FreeA[Ar, FF, C, B])(implicit L: LubC[Ar])
  : FreeA[L.Lub, FF, Either[A, C], B] = Choice(self, fcb)

  /** [[Choose]] */
  final def choose[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C, D]
  (fcd: FreeA[Ar, FF, C, D])(implicit L: LubC[Ar])
  : FreeA[L.Lub, FF, Either[A, C], Either[B, D]] = Choose(self, fcd)

  /** [[Plus]] */
  final def plus[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b]]
  (fcb: FreeA[Ar, FF, A, B])(implicit L: LubP[Ar])
  : FreeA[L.Lub, FF, A, B] = Plus(self, fcb)

  /** [[Split]] */
  final def split[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C, D]
  (fcd: FreeA[Ar, FF, C, D])
  : FreeA[Ar, FF, (A, C), (B, D)] = Split(self, fcd)


  /**
   * Aliases / Utility methods
   */

  final def compose[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C]
  (fca: FreeA[Ar, FF, C, A]): FreeA[Ar, FF, C, B] = fca andThen self

  /** [[compose]] with a lifted function */
  final def lmap[C](f: C => A): FreeA[Arr, F, C, B] = compose(fn(f))

  /** [[andThen]] on lifted function */
  final def rmap[C](f: B => C): FreeA[Arr, F, A, C] = andThen(fn(f))

  /** Alias for [[andThen]] */
  def >>>[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C]
  (fbc: FreeA[Ar, FF, B, C]): FreeA[Ar, FF, A, C] = self.andThen(fbc)

  /** Alias for [[compose]] */
  def <<<[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C]
  (fca: FreeA[Ar, FF, C, A]): FreeA[Ar, FF, C, B] = self compose fca

  /** [[andThen]] with an [[F]] value to be lifted into [[FreeA]] */
  def >>^[FF[a, b] >: F[a, b], C]
  (fbc: FF[B, C]): FreeA[Arr, FF, A, C] = self andThen lift(fbc)

  /** [[compose]] with an [[F]] value to be lifted into [[FreeA]] */
  def <<^[FF[a, b] >: F[a, b], C]
  (fca: FF[C, A]): FreeA[Arr, FF, C, B] = self compose lift(fca)

  /** Alias for [[rmap]] */
  def >^[C](f: B => C): FreeA[Arr, F, A, C] = self.rmap(f)

  /** Alias for [[lmap]] */
  def <^[C](f: C => A): FreeA[Arr, F, C, B] = self.lmap(f)

  /** Alias for [[split]] */
  def ***[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C, D]
  (fcd: FreeA[Ar, FF, C, D]): FreeA[Ar, FF, (A, C), (B, D)] = self.split(fcd)

  /** Alias for [[merge]] */
  def &&&[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C]
  (fac: FreeA[Ar, FF, A, C]): FreeA[Ar, FF, A, (B, C)] = self.merge(fac)

  /** Alias for [[choose]] */
  final def +++[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C, D]
  (fcd: FreeA[Ar, FF, C, D])(implicit L: LubC[Ar]): FreeA[L.Lub, FF, Either[A, C], Either[B, D]] = self.choose(fcd)

  /** Alias for [[choice]] */
  final def |||[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b], C]
  (fcb: FreeA[Ar, FF, C, B])(implicit L: LubC[Ar]): FreeA[L.Lub, FF, Either[A, C], B] = self.choice(fcb)

  /** Alias for [[plus]] */
  final def <+>[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b]]
  (fcb: FreeA[Ar, FF, A, B])(implicit L: Lub[Ar, ArrowPlus]): FreeA[L.Lub, FF, A, B] = self.plus(fcb)

  /** test condition [[B]], Right == true */
  def test(implicit ev: B =:= Boolean): FreeA[Arr, F, A, Either[A, A]] =
    self.|>* >^ (ba => if(ba._1) ba._2.asRight else ba._2.asLeft)

  /** Select first if output is a tuple */
  def _1[C](implicit ev: B <:< (C, Any)): FreeA[Arr, F, A, C] = self.rmap(_._1)

  /** Select second if output is a tuple */
  def _2[C](implicit ev: B <:< (Any, C)): FreeA[Arr, F, A, C] = self.rmap(_._2)

  /** Return a tuple with output [[B]] first and input [[A]] second  */
  def `|>*`: FreeA[Arr, F, A, (B, A)] = self.merge(id)

  /** Return a tuple with input [[A]] first and output [[B]] second  */
  def `|*>`: FreeA[Arr, F, A, (A, B)] = id.merge(self)

  /** Feed input [[A]] to two copies of this arrow and tuple the outputs */
  def `|>>`: FreeA[Arr, F, A, (B, B)] = self.merge(self)

  /** Dead end. Discard the output [[B]] and Return the input [[A]] */
  def `|*`: FreeA[Arr, F, A, A] = self.|>*._2

  /** Appends a dead end arrow */
  def `>|`[Ar[f[_, _]] <: Arr[f], FF[a, b] >: F[a, b]]
  (deadEnd: FreeA[Ar, FF, B, Unit]): FreeA[Ar, FF, A, B] = self.andThen(deadEnd.|*)


  /**
   * If this arrows output is type equivalent to the input, then feed the output to this arrows input n times
   * [[andThen]] is Stack-safe when compiling the [[FreeA]] to some target arrow, but if the targets arrow
   * implementation has a stack-unsafe [[cats.arrow.Arrow.andThen]] implementation, running the interpretation
   * may blow the stack
   *
   * */
  @inline def loopN(n: Int)(implicit ev: B =:= A): FreeA[Arr, F, A, B] = {
    val _ = ev
    val init = self.asInstanceOf[FreeA[Arr, F, B, B]]
    var g = init
    for (_ <- 1 until n) { g = g.andThen(init) }
    g.asInstanceOf[FreeA[Arr, F, A, B]]
  }
}

object FreeA {

  @inline final def id[A]: FA[Nothing, A, A] = Id()

  /** Lift a pure function into [[FAC]]. Can be composed with any context */
  @inline def fn[A, B](f: A => B): FA[Nothing, A, B] = Fn(cats.data.AndThen(f))

  /** Lift an algebra [[F]] into [[FAC]] */
  @inline def lift[F[_, _], A, B](fab: F[A, B]): FA[F, A, B] = Lift(fab)

  @inline def zeroArrow[A, B]: FAZ[Nothing, A, B] = Zero()

  @inline def justLeft[A, B]: FACZ[Nothing, Either[B, A], B] = arrowInstance[ArrowChoiceZero, Nothing].justLeft

  @inline def justRight[A, B]: FACZ[Nothing, Either[A, B], B] = arrowInstance[ArrowChoiceZero, Nothing].justRight

  val fork: FA[Nothing, Boolean, Either[Unit, Unit]] = FreeA.fn((b: Boolean) => if(b) ().asRight else ().asLeft)

  final implicit def arrowInstance[Arr[f[_, _]] <: Arrow[f], F[_, _]]
  (implicit L1: Lub[Arr, ArrowPlus], L2: Lub[Arr, ArrowChoice]): ArrowChoicePlus[FreeA[Arr, F, ?, ?]] =
    new ArrowChoicePlus[FreeA[Arr, F, ?, ?]] {
      def compose[A, B, C](f: FreeA[Arr, F, B, C], g: FreeA[Arr, F, A, B]): FreeA[Arr, F, A, C] = g andThen f
      def lift[A, B](f: A => B): FreeA[Arr, F, A, B] = FreeA.fn(f)
      def first[A, B, C](fa: FreeA[Arr, F, A, B]): FreeA[Arr, F, (A, C), (B, C)] = fa.first
      def plus[A, B](f: FreeA[Arr, F, A, B], g: FreeA[Arr, F, A, B]): FreeA[Arr, F, A, B] =
        (f <+> g).asInstanceOf[FreeA[Arr, F, A, B]]
      def zeroArrow[B, C]: FreeA[Arr, F, B, C] =
        FreeA.zeroArrow[B, C].asInstanceOf[FreeA[Arr, F, B, C]]
      def choose[A, B, C, D](f: FreeA[Arr, F, A, C])(g: FreeA[Arr, F, B, D]): FreeA[Arr, F, Either[A, B], Either[C, D]] =
        (f +++ g).asInstanceOf[FreeA[Arr, F, Either[A, B], Either[C, D]]]
      override def second[A, B, C](fa: FreeA[Arr, F, A, B]): FreeA[Arr, F, (C, A), (C, B)] = fa.second
      override def rmap[A, B, C](fab: FreeA[Arr, F, A, B])(f: B => C): FreeA[Arr, F, A, C] = fab.rmap(f)
      override def lmap[A, B, C](fab: FreeA[Arr, F, A, B])(f: C => A): FreeA[Arr, F, C, B] = fab.lmap(f)
      override def id[A]: FreeA[Arr, F, A, A] = FreeA.id
      override def split[A, B, C, D](f: FreeA[Arr, F, A, B], g: FreeA[Arr, F, C, D]): FreeA[Arr, F, (A, C), (B, D)] = f split g
      override def merge[A, B, C](f: FreeA[Arr, F, A, B], g: FreeA[Arr, F, A, C]): FreeA[Arr, F, A, (B, C)] = f merge g
      override def choice[A, B, C](f: FreeA[Arr, F, A, C], g: FreeA[Arr, F, B, C]): FreeA[Arr, F, Either[A, B], C] =
        (f choice g).asInstanceOf[FreeA[Arr, F, Either[A, B], C]]
    }

  /**
   * This is not functionally necessary, since {{{ FreeA.fn(identity) }}} does the same thing,
   * but encoding it into the GADT comes in handy when introspecting the [[FreeA]] structure since
   * it can be distinguished from other anonymous functions.
   */
  final private case class Id[A]() extends FreeA[Arrow, Nothing, A, A] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: Arrow[G]): G[A, A] = A.id
  }
  final private case class Fn[A, B](f: A => B) extends FreeA[Arrow, Nothing, A, B] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: Arrow[G]): G[A, B] = A.lift(f)
  }
  final private case class Lift[F[_, _], A, B](fab: A F B) extends FreeA[Arrow, F, A, B] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arrow[G]): G[A, B] = fg(fab)
  }
  final private case class AndThen[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B, C](
    begin: FreeA[Arr, F, A, B],
    end: FreeA[Arr, F, B, C]
  ) extends FreeA[Arr, F, A, C] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[A, C] = {
      type EvalG[X, Y] = Eval[G[X, Y]]
      lazy val lazyAnd = new (FreeA[Arr, F, ?, ?] ~~> EvalG) {
        def apply[D, E](f: FreeA[Arr, F, D, E]): EvalG[D, E] = f match {
          case a: AndThen[Arr, F, d, b, e] =>

            for {
              b <- Eval.later(apply(a.begin)).flatten
              e <- apply(a.end)
            } yield b.andThen(e)

          case _ => Eval.now(f.foldMap(fk))
        }
      }

      val eval = for {
        e <- lazyAnd(begin)
        b <- Eval.later(lazyAnd(end)).flatten
      } yield e andThen b
      eval.value
    }
  }
  final private case class Merge[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B, C](
    _first: FreeA[Arr, F, A, B],
    _second: FreeA[Arr, F, A, C]
  ) extends FreeA[Arr, F, A, (B, C)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[A, (B, C)] = A.merge(_first.foldMap(fk), _second.foldMap(fk))
  }
  final private case class Split[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B, C, D](
    _first: FreeA[Arr, F, A, B],
    _second: FreeA[Arr, F, C, D]
  ) extends FreeA[Arr, F, (A, C), (B, D)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[(A, C), (B, D)] = A.split(_first.foldMap(fk), _second.foldMap(fk))
  }
  final private case class First[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B, C](
    _first: FreeA[Arr, F, A, B]
  ) extends FreeA[Arr, F, (A, C), (B, C)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[(A, C), (B, C)] = A.first(_first.foldMap(fk))
  }
  final private case class Second[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B, C](
    _second: FreeA[Arr, F, A, B]) extends FreeA[Arr, F, (C, A), (C, B)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[(C, A), (C, B)] = A.second(_second.foldMap(fk))
  }
  final private case class Choose[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B, C, D](
    _left: FreeA[Arr, F, A, B],
    _right: FreeA[Arr, F, C, D]
  ) extends FreeA[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[A, C], Either[B, D]] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[A, C], Either[B, D]] = A.choose(_left.foldMap(fg))(_right.foldMap(fg))
  }
  final private case class Left[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B, C](
    _left: FreeA[Arr, F, A, B]
  ) extends FreeA[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[A, C], Either[B, C]] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[A, C], Either[B, C]] = A.left(_left.foldMap(fg))
  }
  final private case class Right[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B, C](
    _right: FreeA[Arr, F, A, B]
  ) extends FreeA[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[C, A], Either[C, B]] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[C, A], Either[C, B]] = A.right(_right.foldMap(fg))
  }
  final private case class Choice[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B, C](
    _left: FreeA[Arr, F, A, B],
    _right: FreeA[Arr, F, C, B]
  ) extends FreeA[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[A, C], B] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[A, C], B] = A.choice(_left.foldMap(fg), _right.foldMap(fg))
  }
  final private case class Zero[A, B]() extends FreeA[ArrowZero, Nothing, A, B] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: ArrowZero[G]): G[A, B] = A.zeroArrow
  }
  final private case class Plus[Arr[f[_, _]] <: Arrow[f], F[_, _], A, B](
    f: FreeA[Arr, F, A, B],
    g: FreeA[Arr, F, A, B]
  ) extends FreeA[λ[α[_, _] => Arr[α] with ArrowPlus[α]], F, A, B] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G] with ArrowPlus[G]): G[A, B] = A.plus(f.foldMap(fk), g.foldMap(fk))
  }
}