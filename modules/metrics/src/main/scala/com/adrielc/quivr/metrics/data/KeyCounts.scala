package com.adrielc.quivr.metrics.data

import cats.data.NonEmptyMap
import cats.kernel.{CommutativeSemigroup, Order}
import cats.implicits._
import eu.timepit.refined.cats._
import eu.timepit.refined.auto._

case class KeyCounts[K](counts: NonEmptyMap[K, NonZeroCount]) {

  def +(other: KeyCounts[K])(implicit O: Order[K]): KeyCounts[K] =
    KeyCounts(NonEmptyMap.fromMapUnsafe(counts.toSortedMap |+| other.counts.toSortedMap))

  def binarize: KeyCounts[K] =
    copy(counts = counts.map(_ => 1L))
}
object KeyCounts {

  implicit def semigroup[E: Order]: CommutativeSemigroup[KeyCounts[E]] =
    CommutativeSemigroup.instance(_ + _)
}