package com.adrielc

package object arrow {

  type ~~>[-F[_, _], +G[_, _]] = data.FunctionA[F, G]
  val LazyTuple = data.LazyTuple
  type :&:[A,B] = LazyTuple.LazyTuple2[A,B]
}
