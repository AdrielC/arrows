package com.adrielc.quivr


import cats.arrow.Arrow
import cats.data.{IRWST, Kleisli}
import cats.{Monad, Monoid, MonoidK, SemigroupK}
import cats.implicits._
import com.adrielc.quivr.instances.catsIRWST.IRWSTArrow

object instances {

  object all extends AllInstances

  trait AllInstances extends Instances0 {

    implicit def kleisliArrowChoiceZero[M[_]](implicit M: Monad[M], MK: MonoidK[M]): ArrowChoiceZero[Kleisli[M, *, *]] =
      new kleisli.KleisliArrowChoiceZero[M] {
        val monoidK: MonoidK[M] = MK
        val A: AC[Kleisli[M, *, *]] = Kleisli.catsDataArrowChoiceForKleisli
      }

    implicit def arrowPlusForIRWST[E, L, F[_]]
    (implicit SK: SemigroupK[F], ML: Monoid[L], M: Monad[F]): ArrowChoicePlus[IRWST[F, E, L, *, *, Unit]] =
      new ArrowChoicePlus[IRWST[F, E, L, *, *, Unit]] with IRWSTArrow[F, E, L]  {
        val monad: Monad[F] = M
        val monoid: Monoid[L] = ML

        override def choose[A, B, C, D](f: IRWST[F, E, L, A, C, Unit])(g: IRWST[F, E, L, B, D, Unit]): IRWST[F, E, L, Either[A, B], Either[C, D], Unit] =
          IRWST.apply((e: E, sa: Either[A, B]) => sa.fold(
            a => f.modify(_.asLeft[D]).run(e, a),
            b => g.modify(_.asRight[C]).run(e, b)
          ))

        override def plus[A, B](f: IRWST[F, E, L, A, B, Unit], g: IRWST[F, E, L, A, B, Unit]): IRWST[F, E, L, A, B, Unit] =
          IRWST.apply((e: E, a: A) => f.run(e, a) <+> g.run(e, a))
      }
  }

  trait Instances0 {

    implicit def kleisliArrowChoicePlus[M[_]](implicit M: Monad[M], SK: SemigroupK[M]): ArrowChoicePlus[Kleisli[M, *, *]] =
      new kleisli.KleisliArrowChoicePlus[M] {
        val semigroupK: SemigroupK[M] = SK
        val A: AC[Kleisli[M, *, *]] = Kleisli.catsDataArrowChoiceForKleisli
      }
  }


  object kleisli {

    private[quivr] trait KleisliArrowChoicePlus[M[_]] extends ArrowChoicePlus[Kleisli[M, *, *]] with ComposedArrowChoiceInstance[Kleisli[M, *, *]] {
      implicit def semigroupK: SemigroupK[M]

      def plus[A, B](f: Kleisli[M, A, B], g: Kleisli[M, A, B]): Kleisli[M, A, B] =
        Kleisli[M, A, B](a => semigroupK.combineK(f.run(a), g.run(a)))
    }

    private[quivr] trait KleisliArrowChoiceZero[M[_]] extends KleisliArrowChoicePlus[M] with ArrowChoiceZero[Kleisli[M, *, *]] {
      def monoidK: MonoidK[M]
      def semigroupK: SemigroupK[M] = monoidK
      override def zeroArrow[B, C]: Kleisli[M, B, C] =
        Kleisli(_ => monoidK.empty)
    }
  }



  object catsIRWST {

    private[quivr] trait IRWSTArrow[F[_], E, L] extends Arrow[IRWST[F, E, L, *, *, Unit]] {
      import cats.data.IndexedReaderWriterStateT.catsDataStrongForIRWST

      implicit def monad: Monad[F]
      implicit def monoid: Monoid[L]

      override def split[A, B, C, D](fab: IRWST[F, E, L, A, B, Unit], fcd: IRWST[F, E, L, C, D, Unit]): IRWST[F, E, L, (A, C), (B, D), Unit] =
        IRWST.applyF {
          (fab.runF, fcd.runF).mapN { (a, c) =>
            (e: E, ac: (A, C)) => {
              val bout = a(e, ac._1)
              val dout = c(e, ac._2)
              (bout, dout).mapN { case ((l1, b, _), (l2, d, _)) =>  (l1 |+| l2, (b, d), ()) }
            }
          }
        }

      override def lift[A, B](f: A => B): IRWST[F, E, L, A, B, Unit] =
        IRWST.modify(f)

      override def compose[A, B, C](f: IRWST[F, E, L, B, C, Unit], g: IRWST[F, E, L, A, B, Unit]): IRWST[F, E, L, A, C, Unit] =
        g.flatMap(_ => f)

      override def first[A, B, C](fa: IRWST[F, E, L, A, B, Unit]): IRWST[F, E, L, (A, C), (B, C), Unit] =
        catsDataStrongForIRWST[F, E, L, Unit].first(fa)

      override def second[A, B, C](fa: IRWST[F, E, L, A, B, Unit]): IRWST[F, E, L, (C, A), (C, B), Unit] =
        catsDataStrongForIRWST[F, E, L, Unit].second(fa)

      override def lmap[A, B, C](fab: IRWST[F, E, L, A, B, Unit])(f: C => A): IRWST[F, E, L, C, B, Unit] =
        fab.contramap(f)

      override def rmap[A, B, C](fab: IRWST[F, E, L, A, B, Unit])(f: B => C): IRWST[F, E, L, A, C, Unit] =
        fab.modify(f)
    }
  }
}