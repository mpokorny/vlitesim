package edu.nrao.vlite

import scala.util.Random
import akka.actor.Props

object GaussianValueSource {
  def props(mean: Double, stdDev: Double, seeds: Seq[Long], bufferSize: Int = 1):
      Props =
    ValueSource.props(
      seeds.map(new Random(_)).map(
        r => (() => r.nextGaussian() * stdDev + mean)),
    bufferSize)
}
