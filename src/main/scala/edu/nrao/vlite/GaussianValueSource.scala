package edu.nrao.vlite

import scala.util.Random
import akka.actor.Props

object GaussianValueSource {
  def props(mean: Double, stdDev: Double, seed: Long, bufferSize: Int = 1):
      Props = {
    val rng = new Random(seed)
    ValueSource.props(() => rng.nextGaussian() * stdDev + mean, bufferSize)
  }
}
