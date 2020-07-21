package com.adrielc.arrow
package free

import cats.{Eval, Monoid}
import cats.arrow.{Arrow, ArrowChoice}
import cats.kernel.Semigroup
import com.adrielc.arrow.{ArrowChoicePlus, ArrowPlus, ArrowZero, ~>|, ~~>}
import cats.syntax.{either, flatMap}
import either._
import flatMap._
import com.adrielc.arrow.data.{BiConst, BiEitherK, EnvA}

/** Free Arrow
 *
 * Free construction of an Arrow for any context [[Pipe]] with interpretation requirements of [[R]]
 *
 * @tparam R The capabilities required to interpret/fold this free arrow into [[Pipe]]
 *
 *           These capabilities adjust based on the methods used to create/compose
 *           the free arrow.
 *
 *           Must be a subtype of [[R]] and supertype of [[ArrowChoicePlus]] since those
 *           are the currently supported typeclasses
 * @tparam Pipe The underlying arrow context. Any type of kind (* -> * -> *) e.g. `AST[In, Out]` can be
 *           be composed together using methods from [[R]] to [[ArrowChoicePlus]] without requiring
 *           an instance of the desired type class
 */
sealed trait FreeA[-R[f[_, _]] >: ACP[f] <: AR[f], +Pipe[_, _], In, Out] {
  self =>
  import FreeA._

  /**
   * Evaluate this free arrow to a [[G]] using [[G]]s behavior for
   * the Arrow type [[R]]
   */
  def foldMap[G[_, _]](fg: Pipe ~~> G)(implicit A: R[G]): G[In, Out]

  /**
   * Modify the arrow context `Pipe` using transformation `fg`.
   *
   * This is effectively compiling your free arrow into another
   * language by applying the given `fg` each [[Pipe]]
   *
   * If your binatural transformation is effectful, be careful. These
   * effects will be applied by `compile`.
   */
  final def compile[G[_, _]](fg: Pipe ~~> G): FreeA[R, G, In, Out] =
    foldMap(fg.andThen(lift))

  /**
   * Embed context in arrow coproduct of [[Pipe]] and [[G]]
   * [[Pipe]] on the left
   */
  def inl[G[_, _]]: EitherFreeA[R, Pipe, G, In, Out] =
    compile(BiEitherK.leftK)

  /**
   * Embed context in arrow coproduct of [[Pipe]] and [[G]]
   * [[Pipe]] on the right
   */
  def inr[G[_, _]]: EitherFreeA[R, G, Pipe, In, Out] =
    compile(BiEitherK.rightK)


  final def analyze[M: Monoid](m: Pipe ~>| M): M =
    foldMap(new (Pipe ~~> BiConst[M, ?, ?]) {
      def apply[C, D](f: Pipe[C, D]): BiConst[M, C, D] = BiConst(m(f))
    }).getConst

  /**
   *
   *
   */
  final def optimize[RR[f[_, _]] >: ACP[f] <: AR[f], FF[a, b] >: Pipe[a, b], M: Monoid](
    inspect: FF ~>| M,
    optimize: |~>[M, RR, FF]
  ): FreeA[RR, FF, In, Out] =
    foldMap(EnvA[FF](self.analyze[M](inspect)).andThen(optimize))


  // Combinators

  /** Alias for [[andThen]] */
  def >>>[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b], C](
    fbc: FreeA[RR, FF, Out, C]
  ): FreeA[RR, FF, In, C] =
    self.andThen(fbc)

  /** Alias for [[compose]] */
  def <<<[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b], C](
    fca: FreeA[RR, FF, C, In]
  ): FreeA[RR, FF, C, Out] = self compose fca

  /** Alias for [[rmap]] */
  def >>^[C](f: Out => C): FreeA[R, Pipe, In, C] = rmap(f)

  /** Alias for [[lmap]] */
  def <<^[C](f: C => In): FreeA[R, Pipe, C, Out] = lmap(f)

  /** Alias for [[split]] */
  def ***[RR[f[_, _]] >: AR[f] <: R[f], FF[a, b] >: Pipe[a, b], C, D](
    fcd: FreeA[RR, FF, C, D]
  ): FreeA[RR, FF, (In, C), (Out, D)] =
    self.split[RR, FF, C, D](fcd)

  /** Alias for [[merge]] */
  def &&&[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b], C](
    fac: FreeA[RR, FF, In, C]
  ): FreeA[RR, FF, In, (Out, C)] = self.merge(fac)

  /** Alias for [[choose]] */
  final def +++[RR[f[_, _]] >: AC[f] <: R[f], FF[a, b] >: Pipe[a, b], C, D](
    fcd: FreeA[RR, FF, C, D]
  )(implicit L: |||@[RR]): FreeA[L.Lub, FF, Either[In, C], Either[Out, D]] =
    self.choose(fcd)

  /** Alias for [[choice]] */
  final def |||[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b], C](
    fcb: FreeA[RR, FF, C, Out]
  )(implicit L: |||@[RR]): FreeA[L.Lub, FF, Either[In, C], Out] =
    self.choice[RR, FF, C](fcb)

  /** Alias for [[plus]] */
  final def <+>[RR[f[_, _]] >: AP[f] <: R[f], FF[a, b] >: Pipe[a, b]](
    fcb: FreeA[RR, FF, In, Out]
  )(implicit L: <+>@[RR]): FreeA[L.Lub, FF, In, Out] =
    self.plus(fcb)

  /** Alias for [[and]] */
  def |&|[FF[a, b] >: Pipe[a, b]](
    fab: FreeA[AR, FF, In, Out]
  ): FreeA[ACP, FF, In, Either[Out, Out]] =
    self.and(fab)

  /** [[split]] wwith `fab` and then [[cats.Semigroup.combine]] the tupled [[Out]] */
  def ***|+|[RR[f[_, _]] >: AR[f] <: R[f], FF[a, b] >: Pipe[a, b], A](
    fab: FreeA[RR, FF, A, Out]
  )(implicit S: Semigroup[Out]): FreeA[ACP, FF, (In, A), Out] =
    self.split[RR, FF, A, Out](fab).rmap((S.combine _).tupled)

  /** Select first if output is a tuple */
  def _1[C](implicit ev: Out <:< (C, Any)): FreeA[R, Pipe, In, C] = >>^(_._1)

  /** Select second if output is a tuple */
  def _2[C](implicit ev: Out <:< (Any, C)): FreeA[R, Pipe, In, C] = >>^(_._2)

  /** Return a tuple with output [[Out]] first and input [[In]] second  */
  def `*->*`: FreeA[R, Pipe, In, (Out, In)] =
    self.merge(id)

  /** Return a tuple with input [[In]] first and output [[Out]] second  */
  def `*-*>`: FreeA[R, Pipe, In, (In, Out)] =
    id.merge(self)

  /** Dead end. Discard the output [[Out]] and Return the input [[In]] */
  def `*-*`: FreeA[R, Pipe, In, In] =
    self.`*->*`._2

  /** Feed input [[In]] to two copies of this arrow and tuple the outputs */
  def `=>>`: FreeA[R, Pipe, In, (Out, Out)] =
    self.merge(self)

  /** duplicate the output [[Out]] */
  def `->>`: FreeA[R, Pipe, In, (Out, Out)] =
    self.rmap(o => (o, o))

  /** feed [[Out]] to a dead end arrow, ignoring its output and returning the [[Out]] */
  def ->|[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b]](
    deadEnd: FreeA[RR, FF, Out, Unit]
  ): FreeA[RR, FF, In, Out] =
    self.andThen(deadEnd.*-*)

  /** feed [[Out]] to a dead end arrow, ignoring its output and returning the [[Out]] */
  def ->/[RR[f[_, _]] >: AR[f] <: R[f], FF[a, b] >: Pipe[a, b], A](
    mergeR: FreeA[RR, FF, Unit, A]
  ): FreeA[RR, FF, In, (Out, A)] =
    fn((a: In) => (a, ())).andThen(self.split[RR, FF, Unit, A](mergeR))

  def ->\[RR[f[_, _]] >: AR[f] <: R[f], FF[a, b] >: Pipe[a, b], A](
    mergeL: FreeA[RR, FF, Unit, A]
  ): FreeA[RR, FF, In, (A, Out)] =
    fn((a: In) => ((), a)).andThen(mergeL.split[RR, FF, In, Out](self))


  /** test condition [[Out]], Right == true */
  def test(implicit ev: Out =:= Boolean): FreeA[R, Pipe, In, Either[In, In]] =
    self.*->* >>^ (ba => if(ba._1) ba._2.asRight else ba._2.asLeft)

  /**
   * If this arrows output is type equivalent to the input, then feed the output to this arrows input n times
   * [[andThen]] is Stack-safe when compiling the [[FreeA]] to some target arrow, but if the targets arrow
   * implementation has a stack-unsafe [[cats.arrow.Arrow.andThen]] implementation, running the interpretation
   * may blow the stack
   *
   * */
  def loopN(n: Int)(implicit ev: Out =:= In): FreeA[R, Pipe, In, Out] = {
    val _ = ev
    val init = self.asInstanceOf[FreeA[R, Pipe, Out, Out]]
    var g = init
    for (_ <- 1 until n) { g = g.andThen(init) }
    g.asInstanceOf[FreeA[R, Pipe, In, Out]]
  }

  /**
   * Fuses any pure functions if possible, otherwise wraps the arrows in [[AndThen]]
   */
  def andThen[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b], C](
    fbc: FreeA[RR, FF, Out, C]
  ): FreeA[RR, FF, In, C] =
    (self, fbc) match {
      case (Fn(f), Fn(g)) => Fn(f andThen g)
      case _ => AndThen(self, fbc)
    }

  final def compose[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b], C](
    fca: FreeA[RR, FF, C, In]
  ): FreeA[RR, FF, C, Out] =
    fca.andThen(self)

  /** [[andThen]] on lifted function */
  def rmap[C](f: Out => C): FreeA[R, Pipe, In, C] = andThen(fn(f))

  /** [[compose]] with a lifted function */
  def lmap[C](f: C => In): FreeA[R, Pipe, C, Out] = compose(fn(f))

  final def merge[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b], C](
    fac: FreeA[RR, FF, In, C]
  ): FreeA[RR, FF, In, (Out, C)] =
    Merge(self, fac)

  /** [[First]], equivalent to [[cats.arrow.Strong.first]] */
  final def first[C]: FreeA[R, Pipe, (In, C), (Out, C)] =
    First(self)

  /** [[Second]], equivalent to [[cats.arrow.Strong.second]] */
  final def second[C]: FreeA[R, Pipe, (C, In), (C, Out)] =
    Second(self)

  /** [[Left]] */
  final def left[RR[f[_, _]] >: AC[f] <: R[f], FF[a, b] >: Pipe[a, b], C](implicit L: |||@[RR])
  : FreeA[L.Lub, FF, Either[In, C], Either[Out, C]] = Left[RR, Pipe, In, Out, C](self)

  /** [[Right]] */
  final def right[RR[f[_, _]] >: AC[f] <: R[f], FF[a, b] >: Pipe[a, b], C](implicit L: |||@[RR])
  : FreeA[L.Lub, FF, Either[C, In], Either[C, Out]] = Right[RR, Pipe, In, Out, C](self)

  /** [[Choice]] */
  final def choice[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b], C](
    fcb: FreeA[RR, FF, C, Out]
  )(implicit L: |||@[RR]): FreeA[L.Lub, FF, Either[In, C], Out] =
    Choice[RR, FF, In, Out, C](self, fcb)

  /** [[Choose]] */
  final def choose[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Pipe[a, b], C, D](
    fcd: FreeA[RR, FF, C, D]
  )(implicit L: |||@[RR]): FreeA[L.Lub, FF, Either[In, C], Either[Out, D]] =
    Choose[RR, FF, In, Out, C, D](self, fcd)

  /** [[Split]] */
  final def split[RR[f[_, _]] >: AR[f] <: R[f], FF[a, b] >: Pipe[a, b], C, D](
    fcd: FreeA[RR, FF, C, D]
  ): FreeA[RR, FF, (In, C), (Out, D)] =
    Split(self, fcd)

  /** [[Plus]] */
  final def plus[RR[f[_, _]] >: AP[f] <: R[f], FF[a, b] >: Pipe[a, b]](
    fcb: FreeA[RR, FF, In, Out]
  )(implicit L: <+>@[RR]): FreeA[L.Lub, FF, In, Out] =
    Plus[RR, FF, In, Out](self, fcb)

  def and[FF[a, b] >: Pipe[a, b]](
    fab: FreeA[AR, FF, In, Out]
  ): FreeA[ACP, FF, In, Either[Out, Out]] =
    arrowInstance[R, FF].and(self, fab)
}

object FreeA {

  /** Lift an algebra [[F]] into [[FA]] */
  @inline def lift[F[_, _]]: F ~~> FA[F, ?, ?] = new (F ~~> FA[F, ?, ?]) { def apply[A, B](f: F[A, B]): FA[F, A, B] = Lift(f) }

  /** Lift a pure function into [[FA]]. Can be composed with any arrow context */
  @inline final def id[A]: A >>> A = Id()

  @inline def fn[A, B](f: A => B): A >>> B = Fn(cats.data.AndThen(f))

  @inline def zeroArrow[A, B] : A ~@~ B = Zero()

  @inline def justLeft[A, B]  : B ^|- A = Z.justLeft

  @inline def justRight[A, B] : A -|^ B = Z.justRight

  def toEmpty[A, B: Monoid]   : A >>> B = fn(_ => Monoid.empty)

  val fork: Boolean >>> Either[Unit, Unit] = fn(b => if(b) |^ else ^|)

  def ^[A] = new ^[A]
  class ^[A] private[FreeA] {
    def >>>[B: Monoid]      : A >>> B     = toEmpty
    def ^|-[B]              : A ^|- B     = justLeft
    def ~@~[B]              : A ~@~ B     = zeroArrow
    def -|^[B]              : A -|^ B     = justRight
  }


  implicit class FreeAOps[R[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B](private val fab: FreeA[R, F, A, B]) extends AnyVal {
    final def ||||[RR[f[_, _]] >: AP[f] <: R[f], FF[a, b] >: F[a, b], C](
      fcb: FreeA[RR, FF, C, B]
    )(implicit L: |||@[RR]): FreeA[L.Lub, FF, Either[A, C], B] =
      fab.choice[RR, FF, C](fcb)

    def >>>>[RR[f[_, _]] >: AR[f] <: R[f], FF[a, b] >: F[a, b], C](
      fbc: FreeA[RR, FF, B, C]
    ): FreeA[RR, FF, A, C] =
      fab.andThen(fbc)
  }


  implicit def arrowInstance[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _]]
  : ArrowChoicePlus[FreeA[Arr, F, ?, ?]] with Arr[FreeA[Arr, F, ?, ?]] =
    new ArrowChoicePlus[FreeA[Arr, F, ?, ?]] {
      def compose[A, B, C](f: FreeA[Arr, F, B, C], g: FreeA[Arr, F, A, B]): FreeA[Arr, F, A, C] = g andThen f
      def lift[A, B](f: A => B): FreeA[Arr, F, A, B] = FreeA.fn(f)
      def first[A, B, C](fa: FreeA[Arr, F, A, B]): FreeA[Arr, F, (A, C), (B, C)] = fa.first
      def plus[A, B](f: FreeA[Arr, F, A, B], g: FreeA[Arr, F, A, B]): FreeA[Arr, F, A, B] =
        Plus(f.asInstanceOf[FP[F, A, B]], g.asInstanceOf[FP[F, A, B]]).asInstanceOf[FreeA[Arr, F, A, B]]
      def zeroArrow[B, C]: FreeA[Arr, F, B, C] =
        FreeA.zeroArrow[B, C].asInstanceOf[FreeA[Arr, F, B, C]]
      def choose[A, B, C, D](f: FreeA[Arr, F, A, C])(g: FreeA[Arr, F, B, D]): FreeA[Arr, F, Either[A, B], Either[C, D]] =
        Choose(f, g).asInstanceOf[FreeA[Arr, F, Either[A, B], Either[C, D]]]
      override def second[A, B, C](fa: FreeA[Arr, F, A, B]): FreeA[Arr, F, (C, A), (C, B)] = fa.second
      override def rmap[A, B, C](fab: FreeA[Arr, F, A, B])(f: B => C): FreeA[Arr, F, A, C] = fab.>>^(f)
      override def lmap[A, B, C](fab: FreeA[Arr, F, A, B])(f: C => A): FreeA[Arr, F, C, B] = fab.<<^(f)
      override def id[A]: FreeA[Arr, F, A, A] = FreeA.id
      override def split[A, B, C, D](f: FreeA[Arr, F, A, B], g: FreeA[Arr, F, C, D]): FreeA[Arr, F, (A, C), (B, D)] =
        f.asInstanceOf[FA[F, A, B]].split(g.asInstanceOf[FA[F, C, D]])
      override def merge[A, B, C](f: FreeA[Arr, F, A, B], g: FreeA[Arr, F, A, C]): FreeA[Arr, F, A, (B, C)] = f merge g
      override def choice[A, B, C](f: FreeA[Arr, F, A, C], g: FreeA[Arr, F, B, C]): FreeA[Arr, F, Either[A, B], C] =
        Choice(f, g).asInstanceOf[FreeA[Arr, F, Either[A, B], C]]
    }

  /**
   * This is not functionally necessary, since {{{ FreeA.fn(identity) }}} does the same thing,
   * but encoding it into the GADT comes in handy when introspecting the [[FreeA]] structure since
   * it can be distinguished from other anonymous functions.
   */
  final private[free] case class Id[A]() extends FreeA[Arrow, Nothing, A, A] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: Arrow[G]): G[A, A] = A.id
  }
  final private[free] case class Fn[A, B](f: A => B) extends FreeA[Arrow, Nothing, A, B] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: Arrow[G]): G[A, B] = A.lift(f)
  }
  final private[free] case class Lift[F[_, _], A, B](fab: A F B) extends FreeA[Arrow, F, A, B] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arrow[G]): G[A, B] = fg(fab)
  }
  final private[free] case class AndThen[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
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
            } yield A.andThen(b, e)

          case _ => Eval.now(f.foldMap(fk))
        }
      }

      val eval = for {
        e <- lazyAnd(begin)
        b <- Eval.later(lazyAnd(end)).flatten
      } yield A.andThen(e, b)
      eval.value
    }
  }
  final private[free] case class Merge[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _first: FreeA[Arr, F, A, B],
    _second: FreeA[Arr, F, A, C]
  ) extends FreeA[Arr, F, A, (B, C)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[A, (B, C)] =
      A.merge(_first.foldMap(fk), _second.foldMap(fk))
  }
  final private[free] case class Split[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C, D](
    _first: FreeA[Arr, F, A, B],
    _second: FreeA[Arr, F, C, D]
  ) extends FreeA[Arr, F, (A, C), (B, D)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[(A, C), (B, D)] =
      A.split(_first.foldMap(fk), _second.foldMap(fk))
  }
  final private[free] case class First[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _first: FreeA[Arr, F, A, B]
  ) extends FreeA[Arr, F, (A, C), (B, C)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[(A, C), (B, C)] =
      A.first(_first.foldMap(fk))
  }
  final private[free] case class Second[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _second: FreeA[Arr, F, A, B]) extends FreeA[Arr, F, (C, A), (C, B)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[(C, A), (C, B)] =
      A.second(_second.foldMap(fk))
  }
  final private[free] case class Choose[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C, D](
    _left: FreeA[Arr, F, A, B],
    _right: FreeA[Arr, F, C, D]
  ) extends FreeA[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[A, C], Either[B, D]] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[A, C], Either[B, D]] =
      A.choose(_left.foldMap(fg))(_right.foldMap(fg))
  }
  final private[free] case class Left[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _left: FreeA[Arr, F, A, B]
  ) extends FreeA[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[A, C], Either[B, C]] {

    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[A, C], Either[B, C]] =
      A.left(_left.foldMap(fg))
  }
  final private[free] case class Right[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _right: FreeA[Arr, F, A, B]
  ) extends FreeA[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[C, A], Either[C, B]] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[C, A], Either[C, B]] =
      A.right(_right.foldMap(fg))
  }
  final private[free] case class Choice[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _left: FreeA[Arr, F, A, B],
    _right: FreeA[Arr, F, C, B]
  ) extends FreeA[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[A, C], B] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[A, C], B] =
      A.choice(_left.foldMap(fg), _right.foldMap(fg))
  }
  final private[free] case class Zero[A, B]() extends FreeA[ArrowZero, Nothing, A, B] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: ArrowZero[G]): G[A, B] =
      A.zeroArrow
  }
  final private[free] case class Plus[R[f[_, _]] >: AP[f] <: AR[f], F[_, _], A, B](
    f: FreeA[R, F, A, B],
    g: FreeA[R, F, A, B]
  ) extends FreeA[λ[α[_, _] => R[α] with ArrowPlus[α]], F, A, B] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: R[G] with ArrowPlus[G]): G[A, B] =
      A.plus(f.foldMap(fk), g.foldMap(fk))
  }

  private val Z = arrowInstance[ArrowChoiceZero, Nothing]
}
