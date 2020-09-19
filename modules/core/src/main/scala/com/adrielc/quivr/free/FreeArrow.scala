package com.adrielc.quivr.free

import cats.{Eval, Monoid}
import cats.arrow.{Arrow, ArrowChoice}
import cats.kernel.Semigroup
import com.adrielc.quivr.{ArrowChoicePlus, ArrowChoiceZero, ArrowPlus, ArrowZero}
import cats.syntax.{either, flatMap}, either._, flatMap._
import com.adrielc.quivr.data.{BiConst, BiEitherK, BiFunctionK, EnvA, ~>|, ~~>}

/** Free Arrow
 *
 * Free construction of an Arrow for any context [[Flow]] with interpretation requirements of [[R]]
 *
 * @tparam R The capabilities required to interpret/fold this free arrow into [[Flow]]
 *
 *           These capabilities adjust based on the methods used to create/compose
 *           the free arrow.
 *
 *           Must be a subtype of [[Arrow]] and supertype of [[ArrowChoicePlus]] since those
 *           are the currently supported typeclasses
 *
 * @tparam Flow The underlying arrow context. Any type of kind (* -> * -> *) e.g. `AST[In, Out]` can be
 *              be composed together using methods from [[R]] to [[ArrowChoicePlus]] without requiring
 *              an instance of the desired type class
 */
sealed abstract class FreeArrow[-R[f[_, _]] >: ArrowChoicePlus[f] <: Arrow[f], +Flow[_, _], In, Out] {
  self =>
  import FreeArrow._

  /**
   * Evaluate this free arrow to a [[G]] using [[G]]s behavior for
   * the Arrow type [[R]]
   */
  def foldMap[G[_, _]](fg: Flow ~~> G)(implicit A: R[G]): G[In, Out]

  def fold[FF[a, b] >: Flow[a, b]](implicit A: R[FF]): FF[In, Out] = foldMap(BiFunctionK.id[FF])

  /**
   * Modify the arrow context `Flow` using transformation `fg`.
   *
   * This is effectively compiling your free arrow into another
   * language by applying the given `fg` each [[Flow]]
   *
   * If your binatural transformation is effectful, be careful. These
   * effects will be applied by `compile`.
   */
  final def compile[G[_, _]](fg: Flow ~~> G): FreeArrow[R, G, In, Out] =
    foldMap(fg.andThen(BiFunctionK.lift(liftK)))

  /** Fold this [[FreeArrow]] into a summary value using [[M]]s Monoidal behavior */
  final def analyze[M: Monoid](m: Flow ~>| M): M =
    foldMap(new (Flow ~~> BiConst[M, *, *]) {
      def apply[C, D](f: Flow[C, D]): BiConst[M, C, D] = BiConst(m(f))
    }).getConst

  /**
   *
   * Optimize/rewrite this FreeA by using a summary value [[M]].
   *
   * @param inspect  A binatural function from [[Flow]] to some monoid [[M]]
   *                 that will be folded over this structure to create a summary value.
   *                 This is lazily executed, and will only fold over the structure if
   *                 the [[M]] value is used in `optimize`
   * @param optimize A binatural function that can use the summary [[M]] to
   *                 rewrite [[Flow]] into a new [[FreeArrow]].
   *
   */
  final def optimize[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], M: Monoid](
    inspect: FF ~>| M,
    optimize: |~>[M, RR, FF]
  ): FreeArrow[RR, FF, In, Out] =
    foldMap(EnvA(analyze(inspect)).andThen(optimize))

  /**
   * Embed context in arrow coproduct of [[Flow]] and [[G]]
   * [[Flow]] on the left
   */
  def inl[G[_, _]]: EitherFreeA[R, Flow, G, In, Out] =
    compile(BiEitherK.leftK)

  /**
   * Embed context in arrow coproduct of [[Flow]] and [[G]]
   * [[Flow]] on the right
   */
  def inr[G[_, _]]: EitherFreeA[R, G, Flow, In, Out] =
    compile(BiEitherK.rightK)

  // Combinators

  /** Alias for [[andThen]] */
  def >>>[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fbc: FreeArrow[RR, FF, Out, C]
  ): FreeArrow[RR, FF, In, C] =
    self.andThen(fbc)

  /** Alias for [[compose]] */
  def <<<[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fca: FreeArrow[RR, FF, C, In]
  ): FreeArrow[RR, FF, C, Out] =
    self.compose(fca)

  /** Alias for [[rmap]] */
  def >>^[C](f: Out => C): FreeArrow[R, Flow, In, C] =
    self.rmap(f)

  /** Alias for [[lmap]] */
  def <<^[C](f: C => In): FreeArrow[R, Flow, C, Out] =
    self.lmap(f)

  /** Alias for [[split]] */
  def ***[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C, D](
    fcd: FreeArrow[RR, FF, C, D]
  ): FreeArrow[RR, FF, (In, C), (Out, D)] =
    self.split(fcd)

  /** Alias for [[merge]] */
  def &&&[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fac: FreeArrow[RR, FF, In, C]
  ): FreeArrow[RR, FF, In, (Out, C)] =
    self.merge(fac)

  /** Alias for [[choose]] */
  final def +++[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C, D](
    fcd: FreeArrow[RR, FF, C, D]
  )(implicit L: |||@[RR]): FreeArrow[L.Lub, FF, Either[In, C], Either[Out, D]] =
    self.choose(fcd)

  /** Alias for [[choice]] */
  final def |||[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fcb: FreeArrow[RR, FF, C, Out]
  )(implicit L: |||@[RR]): FreeArrow[L.Lub, FF, Either[In, C], Out] =
    self.choice(fcb)

  /** Alias for [[plus]] */
  final def <+>[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b]](
    fcb: FreeArrow[RR, FF, In, Out]
  )(implicit L: <+>@[RR]): FreeArrow[L.Lub, FF, In, Out] =
    self.plus(fcb)

  /** Alias for [[and]] */
  def |&|[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fab: FreeArrow[RR, FF, In, C]
  ): FreeArrow[ACP, FF, In, Either[Out, C]] =
    self.and(fab)

  /** [[split]] wwith `fab` and then [[cats.Semigroup.combine]] the tupled [[Out]] */
  def ***|+|[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], A](
    fab: FreeArrow[RR, FF, A, Out]
  )(implicit S: Semigroup[Out]): FreeArrow[ACP, FF, (In, A), Out] =
    self.split(fab).rmap((S.combine _).tupled)

  /** [[merge]] wwith `fab` and then [[cats.Semigroup.combine]] the tupled [[Out]] */
  def &&&|+|[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], A](
    fab: FreeArrow[RR, FF, In, Out]
  )(implicit S: Semigroup[Out]): FreeArrow[ACP, FF, In, Out] =
    self.merge(fab).rmap((S.combine _).tupled)


  /** Select first if output is a tuple */
  def _1[C](implicit ev: Out <:< (C, Any)): FreeArrow[R, Flow, In, C] =
    self.rmap(_._1)

  /** Select second if output is a tuple */
  def _2[C](implicit ev: Out <:< (Any, C)): FreeArrow[R, Flow, In, C] =
    self.rmap(_._2)

  /** Return a tuple with output [[Out]] first and input [[In]] second  */
  def `*->*`: FreeArrow[R, Flow, In, (Out, In)] =
    self.merge(id)

  /** Return a tuple with input [[In]] first and output [[Out]] second  */
  def `*-*>`: FreeArrow[R, Flow, In, (In, Out)] =
    id.merge(self)

  /** Dead end. Discard the output [[Out]] and Return the input [[In]] */
  def `*-*`: FreeArrow[R, Flow, In, In] =
    self.*->*._2

  /** Feed input [[In]] to two copies of this arrow and tuple the outputs */
  def `*=>>`: FreeArrow[R, Flow, In, (Out, Out)] =
    self.merge(self)

  /** duplicate the output [[Out]] */
  def `*->>`: FreeArrow[R, Flow, In, (Out, Out)] =
    self.rmap(o => (o, o))

  /** feed [[Out]] to a dead end arrow, ignoring its output and returning the [[Out]] */
  def >>|[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b]](
    deadEnd: FreeArrow[RR, FF, Out, Unit]
  ): FreeArrow[RR, FF, In, Out] =
    self.andThen(deadEnd.*-*)

  /** feed [[Monoid.empty]] to the input of [[mergeR]], and thread it's output tupled right of [[Out]] */
  def >>/[RR[f[_, _]] >: AR[f] <: R[f], FF[a, b] >: Flow[a, b], I: Monoid, A](
    mergeR: FreeArrow[RR, FF, I, A]
  ): FreeArrow[RR, FF, In, (Out, A)] =
    lift((a: In) => (a, Monoid.empty)).andThen(self.split(mergeR))

  /** feed [[Monoid.empty]] to the input of [[mergeL]], and thread it's output tupled left of [[Out]] */
  def >>\[RR[f[_, _]] >: AR[f] <: R[f], FF[a, b] >: Flow[a, b], I: Monoid, A](
    mergeL: FreeArrow[RR, FF, I, A]
  ): FreeArrow[RR, FF, In, (A, Out)] =
    lift((a: In) => (Monoid.empty, a)).andThen(mergeL.split(self))


  /** feed [[Out]] to a dead end arrow, ignoring its output and returning the [[Out]] */
  def tap(tap: Out => Unit): FreeArrow[R, Flow, In, Out] =
    self >>| lift(tap)


  /** test condition [[Out]], Right == true */
  def test(implicit ev: Out =:= Boolean): FreeArrow[R, Flow, In, Either[In, In]] =
    self.*->*.rmap(ba => if(ba._1) ba._2.asRight else ba._2.asLeft)

  /**
   * If this arrows output is type equivalent to the input, then feed the output to this arrows input n times
   * [[andThen]] is Stack-safe when compiling the [[FreeArrow]] to some target arrow, but if the targets arrow
   * implementation has a stack-unsafe [[cats.arrow.Arrow.andThen]] implementation, running the interpretation
   * may blow the stack
   *
   * */
  def loopN(n: Int)(implicit ev: Out =:= In): FreeArrow[R, Flow, In, Out] = {
    val _ = ev
    val init = self.asInstanceOf[FreeArrow[R, Flow, Out, Out]]
    var g = init
    for (_ <- 1 until n) { g = g.andThen(init) }
    g.asInstanceOf[FreeArrow[R, Flow, In, Out]]
  }

  /**
   * Fuses any pure functions if possible, otherwise wraps the arrows in [[AndThen]]
   */
  def andThen[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fbc: FreeArrow[RR, FF, Out, C]
  ): FreeArrow[RR, FF, In, C] =
    (self, fbc) match {
      case (Lift(f), Lift(g)) => Lift(f andThen g)
      case _ => AndThen(self, fbc)
    }

  final def compose[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fca: FreeArrow[RR, FF, C, In]
  ): FreeArrow[RR, FF, C, Out] =
    fca.andThen(self)

  /** [[andThen]] on lifted function */
  def rmap[C](f: Out => C): FreeArrow[R, Flow, In, C] =
    self.andThen(lift(f))

  /** [[compose]] with a lifted function */
  def lmap[C](f: C => In): FreeArrow[R, Flow, C, Out] =
    self.compose(lift(f))

  final def merge[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fac: FreeArrow[RR, FF, In, C]
  ): FreeArrow[RR, FF, In, (Out, C)] =
    Merge(self, fac)

  /** [[First]], equivalent to [[cats.arrow.Strong.first]] */
  final def first[C]: FreeArrow[R, Flow, (In, C), (Out, C)] =
    First(self)

  /** [[Second]], equivalent to [[cats.arrow.Strong.second]] */
  final def second[C]: FreeArrow[R, Flow, (C, In), (C, Out)] =
    Second(self)

  /** [[Left]] */
  final def left[C](implicit L: |||@[R]): FreeArrow[L.Lub, Flow, Either[In, C], Either[Out, C]] =
    Left(self)

  /** [[Right]] */
  final def right[C](implicit L: |||@[R]): FreeArrow[L.Lub, Flow, Either[C, In], Either[C, Out]] =
    Right(self)

  /** [[Choice]] */
  final def choice[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fcb: FreeArrow[RR, FF, C, Out]
  )(implicit L: |||@[RR]): FreeArrow[L.Lub, FF, Either[In, C], Out] =
    Choice(self, fcb)

  /** [[Choose]] */
  final def choose[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C, D](
    fcd: FreeArrow[RR, FF, C, D]
  )(implicit L: |||@[RR]): FreeArrow[L.Lub, FF, Either[In, C], Either[Out, D]] =
    Choose(self, fcd)

  /** [[Split]] */
  final def split[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C, D](
    fcd: FreeArrow[RR, FF, C, D]
  ): FreeArrow[RR, FF, (In, C), (Out, D)] =
    Split(self, fcd)

  /** [[Plus]] */
  final def plus[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b]](
    fcb: FreeArrow[RR, FF, In, Out]
  )(implicit L: <+>@[RR]): FreeArrow[L.Lub, FF, In, Out] =
    Plus[RR, FF, In, Out](self, fcb)

  /** [[Plus]] */
  final def or[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b]](
    fcb: FreeArrow[RR, FF, In, Out]
  )(implicit L: <+>@[RR]): FreeArrow[L.Lub, FF, In, Out] =
    self <+> fcb

  def and[RR[f[_, _]] >: ACP[f] <: R[f], FF[a, b] >: Flow[a, b], C](
    fab: FreeArrow[RR, FF, In, C]
  ): FreeArrow[ACP, FF, In, Either[Out, C]] =
    And(self, fab)
}

object FreeArrow {

  def apply[A]: A >>> A = id

  /** Lift a pure function into [[FA]]. Can be composed with any arrow context */
  @inline final def id[A]: A >>> A = Id()

  /** Lift a pure function into into [[FA]] */
  @inline def lift[A, B](f: A => B): A >>> B = Lift(cats.data.AndThen(f))

  /** Lift an [[F]] into [[FA]] */
  @inline def liftK[F[_, _], A, B](fab: F[A, B]): FA[F, A, B] = LiftK(fab)

  @inline def zeroArrow[A, B]: A ~@~ B = Zero()

  @inline def justLeft[A, B]: B ^|- A = Z.justLeft

  @inline def justRight[A, B]: A -|^ B = Z.justRight

  /** Lift a plain value into into [[FA]] */
  @inline def const[A, B](value: B): A >>> B = Const(value)

  /** Always evaluate [[B]] */
  @inline def always[A, B](eval: => B): A >>> B = Always(Eval.always(eval))


  implicit def arrowInstance[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _]]
  : ArrowChoicePlus[FreeArrow[Arr, F, *, *]] with Arr[FreeArrow[Arr, F, *, *]] =
    new ArrowChoicePlus[FreeArrow[Arr, F, *, *]] {

      def compose[A, B, C](f: FreeArrow[Arr, F, B, C], g: FreeArrow[Arr, F, A, B]): FreeArrow[Arr, F, A, C] =
        g.andThen(f)

      def lift[A, B](f: A => B): FreeArrow[Arr, F, A, B] =
        FreeArrow.lift(f)

      def first[A, B, C](fa: FreeArrow[Arr, F, A, B]): FreeArrow[Arr, F, (A, C), (B, C)] =
        fa.first

      def plus[A, B](f: FreeArrow[Arr, F, A, B], g: FreeArrow[Arr, F, A, B]): FreeArrow[Arr, F, A, B] =
        Plus(f, g).asInstanceOf[FreeArrow[Arr, F, A, B]]

      def zeroArrow[B, C]: FreeArrow[Arr, F, B, C] =
        FreeArrow.zeroArrow[B, C].asInstanceOf[FreeArrow[Arr, F, B, C]]

      def choose[A, B, C, D](f: FreeArrow[Arr, F, A, C])(g: FreeArrow[Arr, F, B, D]): FreeArrow[Arr, F, Either[A, B], Either[C, D]] =
        Choose(f, g).asInstanceOf[FreeArrow[Arr, F, Either[A, B], Either[C, D]]]

      override def second[A, B, C](fa: FreeArrow[Arr, F, A, B]): FreeArrow[Arr, F, (C, A), (C, B)] =
        fa.second

      override def rmap[A, B, C](fab: FreeArrow[Arr, F, A, B])(f: B => C): FreeArrow[Arr, F, A, C] =
        fab.rmap(f)

      override def lmap[A, B, C](fab: FreeArrow[Arr, F, A, B])(f: C => A): FreeArrow[Arr, F, C, B] =
        fab.lmap(f)

      override def id[A]: FreeArrow[Arr, F, A, A] =
        FreeArrow.id

      override def split[A, B, C, D](f: FreeArrow[Arr, F, A, B], g: FreeArrow[Arr, F, C, D]): FreeArrow[Arr, F, (A, C), (B, D)] =
        f.split(g)

      override def merge[A, B, C](f: FreeArrow[Arr, F, A, B], g: FreeArrow[Arr, F, A, C]): FreeArrow[Arr, F, A, (B, C)] =
        f.merge(g)

      override def choice[A, B, C](f: FreeArrow[Arr, F, A, C], g: FreeArrow[Arr, F, B, C]): FreeArrow[Arr, F, Either[A, B], C] =
        Choice(f, g).asInstanceOf[FreeArrow[Arr, F, Either[A, B], C]]

      override def and[A, B, C](f: FreeArrow[Arr, F, A, B], g: FreeArrow[Arr, F, A, C]): FreeArrow[Arr, F, A, Either[B, C]] =
        f.and(g).asInstanceOf[FreeArrow[Arr, F, A, Either[B, C]]]
    }

  /**
   * This is not functionally necessary, since {{{ FreeA.fn(identity) }}} does the same thing,
   * but encoding it into the GADT comes in handy when introspecting the [[FreeArrow]] structure since
   * it can be distinguished from other anonymous functions.
   */
  final private case class Id[A]() extends FreeArrow[Arrow, Nothing, A, A] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: Arrow[G]): G[A, A] = A.id
  }
  final private case class Lift[A, B](f: A => B) extends FreeArrow[Arrow, Nothing, A, B] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: Arrow[G]): G[A, B] = A.lift(f)
  }
  final private case class Const[A, B](value: B) extends FreeArrow[Arrow, Nothing, A, B] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: Arrow[G]): G[A, B] = A.lift(_ => value)
  }
  final private case class Always[A, B](eval: Eval[B]) extends FreeArrow[Arrow, Nothing, A, B] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: Arrow[G]): G[A, B] = A.lift(_ => eval.value)
  }
  final private case class LiftK[F[_, _], A, B](fab: F[A, B]) extends FreeArrow[Arrow, F, A, B] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arrow[G]): G[A, B] = fg(fab)
  }
  final private case class AndThen[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    begin: FreeArrow[Arr, F, A, B],
    end: FreeArrow[Arr, F, B, C]
  ) extends FreeArrow[Arr, F, A, C] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[A, C] = {
      type EvalG[X, Y] = Eval[G[X, Y]]
      lazy val lazyAnd = new (FreeArrow[Arr, F, *, *] ~~> EvalG) {
        def apply[D, E](f: FreeArrow[Arr, F, D, E]): EvalG[D, E] = f match {
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
  final private case class Merge[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _first: FreeArrow[Arr, F, A, B],
    _second: FreeArrow[Arr, F, A, C]
  ) extends FreeArrow[Arr, F, A, (B, C)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[A, (B, C)] =
      A.merge(_first.foldMap(fk), _second.foldMap(fk))
  }
  final private case class Split[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C, D](
    _first: FreeArrow[Arr, F, A, B],
    _second: FreeArrow[Arr, F, C, D]
  ) extends FreeArrow[Arr, F, (A, C), (B, D)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[(A, C), (B, D)] =
      A.split(_first.foldMap(fk), _second.foldMap(fk))
  }
  final private case class First[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _first: FreeArrow[Arr, F, A, B]
  ) extends FreeArrow[Arr, F, (A, C), (B, C)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[(A, C), (B, C)] =
      A.first(_first.foldMap(fk))
  }
  final private case class Second[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _second: FreeArrow[Arr, F, A, B]) extends FreeArrow[Arr, F, (C, A), (C, B)] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: Arr[G]): G[(C, A), (C, B)] =
      A.second(_second.foldMap(fk))
  }
  final private case class Choose[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C, D](
    _left: FreeArrow[Arr, F, A, B],
    _right: FreeArrow[Arr, F, C, D]
  ) extends FreeArrow[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[A, C], Either[B, D]] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[A, C], Either[B, D]] =
      A.choose(_left.foldMap(fg))(_right.foldMap(fg))
  }
  final private case class Left[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _left: FreeArrow[Arr, F, A, B]
  ) extends FreeArrow[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[A, C], Either[B, C]] {

    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[A, C], Either[B, C]] =
      A.left(_left.foldMap(fg))
  }
  final private case class Right[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _right: FreeArrow[Arr, F, A, B]
  ) extends FreeArrow[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[C, A], Either[C, B]] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[C, A], Either[C, B]] =
      A.right(_right.foldMap(fg))
  }
  final private case class Choice[Arr[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    _left: FreeArrow[Arr, F, A, B],
    _right: FreeArrow[Arr, F, C, B]
  ) extends FreeArrow[λ[α[_, _] => Arr[α] with ArrowChoice[α]], F, Either[A, C], B] {
    def foldMap[G[_, _]](fg: F ~~> G)(implicit A: Arr[G] with ArrowChoice[G]): G[Either[A, C], B] =
      A.choice(_left.foldMap(fg), _right.foldMap(fg))
  }
  final private case class Zero[A, B]() extends FreeArrow[ArrowZero, Nothing, A, B] {
    def foldMap[G[_, _]](fg: Nothing ~~> G)(implicit A: ArrowZero[G]): G[A, B] =
      A.zeroArrow
  }
  final private case class Plus[R[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B](
    f: FreeArrow[R, F, A, B],
    g: FreeArrow[R, F, A, B]
  ) extends FreeArrow[λ[α[_, _] => R[α] with ArrowPlus[α]], F, A, B] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: R[G] with ArrowPlus[G]): G[A, B] =
      A.plus(f.foldMap(fk), g.foldMap(fk))
  }
  final private case class And[R[f[_, _]] >: ACP[f] <: AR[f], F[_, _], A, B, C](
    f: FreeArrow[R, F, A, B],
    g: FreeArrow[R, F, A, C]
  ) extends FreeArrow[ArrowChoicePlus, F, A, Either[B, C]] {
    def foldMap[G[_, _]](fk: F ~~> G)(implicit A: ACP[G]): G[A, Either[B, C]] =
      A.and(f.foldMap(fk), g.foldMap(fk))
  }

  private val Z = arrowInstance[ArrowChoiceZero, Nothing]
}