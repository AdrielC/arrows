package com.adrielc.quivr.metrics
package ranking

import cats.Contravariant
import cats.data.{NonEmptyList, NonEmptyVector}
import com.adrielc.quivr.metrics.data.Rankings.Ranked
import com.adrielc.quivr.metrics.function._
import com.adrielc.quivr.metrics.result.Results.NonEmptyResults
import com.adrielc.quivr.metrics.result.{GroundTruth, Relevancy, ResultLabels, Results}
import com.adrielc.quivr.metrics.retrieval.TruePositiveCount
import simulacrum.typeclass
import eu.timepit.refined.auto._

/**
 * Represents a complete list of results with their associated relevancies
 *
 * @tparam A
 */
@typeclass trait ResultRelevancies[A] extends RankedRelevancies[A] with TruePositiveCount[A] {
  import result.AtK.ops._
  import retrieval.TruePositiveCount.ops._

  type Rel

  implicit def rel: Relevancy[Rel]

  def resultRelevancies(a: A): NonEmptyVector[Rel]

  final def dcg(a: A, g: GainFn = gain.pow2, d: DiscountFn = discount.log2): Double =
    calcDcg(gains(a), g, d)

  final def ndcg(a: A, g: GainFn = gain.pow2, d: DiscountFn = discount.log2): Option[Double] =
    calcNdcg(gains(a), g, d)

  def precisionAtK(a: A, k: Rank): Option[Double] =
    resultRelevancies(a).atK(k).flatMap(_.precision)

  def recallAtK(a: A, k: Rank): Option[Double] = {
    val rels = resultRelevancies(a)
    val countRel = rels.toVector.count(rel.isRel)
    resultRelevancies(a).atK(k).flatMap { rels =>
      safeDiv(rels.toVector.count(rel.isRel).toDouble, countRel.toDouble)
    }
  }

  def fScoreAtK(a: A, k: Rank): Option[Double] =
    for {
      r <- recallAtK(a, k)
      p <- precisionAtK(a, k)
      plus = r + p
      if plus != 0
    } yield (2 * (r * p)) / plus

  def rPrecision(a: A): Option[Double] =
    Rank.from(truePositiveCount(a)).toOption
      .flatMap(precisionAtK(a, _))

  override def resultCount(a: A): Int =
    resultRelevancies(a).length

  override def rankedRelevancies(a: A): Ranked[Rel] =
    Ranked(resultRelevancies(a))

  private def gains(a: A): NonEmptyVector[Double] =
    resultRelevancies(a).map(rel.gainOrZero)

  override def truePositiveCount(a: A): Int =
    resultRelevancies(a).toVector.count(rel.isRel)
}

object ResultRelevancies {
  type Aux[A, R] = ResultRelevancies[A] {type Rel = R}

  def instance[A, R: Relevancy](rels: A => NonEmptyVector[R]): ResultRelevancies.Aux[A, R] = new ResultRelevancies[A] {
    override type Rel = R
    override val rel: Relevancy[R] = Relevancy[R]

    override def resultRelevancies(a: A): NonEmptyVector[Rel] = rels(a)
  }

  implicit def identityLabelledSet[R: Relevancy]: ResultRelevancies.Aux[NonEmptyVector[R], R] = instance(identity)

  implicit def nelLabelledSet[R: Relevancy]: ResultRelevancies.Aux[NonEmptyList[R], R] = instance(nel => NonEmptyVector(nel.head, nel.tail.toVector))

  implicit def binaryJudgementsFromGroundTruth[S: NonEmptyResults : GroundTruth : ResultLabels]: ResultRelevancies.Aux[S, Option[Label]] = new ResultRelevancies[S] {
    type Rel = Option[Label]
    override val rel: Relevancy[Option[Label]] = Relevancy[Option[Label]]

    def resultRelevancies(a: S): NonEmptyVector[Rel] = NonEmptyResults[S].nonEmptyLabeledResults(a)

    override def truePositiveCount(a: S): Int = {
      val rels = GroundTruth[S].groundTruth(a).set
      Results[S].results(a).count(rels.contains)
    }
  }

  implicit def contravariantRels[R]: Contravariant[ResultRelevancies.Aux[*, R]] = new Contravariant[ResultRelevancies.Aux[*, R]] {
    def contramap[A, B](fa: ResultRelevancies.Aux[A, R])(f: B => A): ResultRelevancies.Aux[B, R] = new ResultRelevancies[B] {
      override type Rel = R
      override val rel: Relevancy[R] = fa.rel

      def resultRelevancies(a: B): NonEmptyVector[Rel] = fa.resultRelevancies(f(a))
    }
  }
}
