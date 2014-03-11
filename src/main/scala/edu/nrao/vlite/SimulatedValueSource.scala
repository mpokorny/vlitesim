package edu.nrao.vlite

import akka.actor._

class SimulatedValueSource(
  seed: Long,
  val mean: Double,
  val stdDev: Double,
  val filter: Vector[Double],
  val bufferSize: Int) extends ValueSourceBase[Byte] {

  import context._

  val gaussianSource = actorOf(GaussianValueSource.props(mean, stdDev, seed))

  var gaussianRVs: Vector[Double] = Vector.empty

  val valueRatio = (1, 1)
  
  def requestValues(n: Int) {
    gaussianSource ! ValueSource.Get(n + filter.length - 1 - gaussianRVs.length)
  }

  private def filtered(rvs: Vector[Double]): Byte = {
    val d = ((0.0, filter) /: rvs) {
      case ((acc, c +: cs), rv) => (acc + c * rv, cs)
    } match {
      case (sum, _) => sum
    }
    ((d max 0.0) min 255.0).toByte
  }

  def receiveValues(as: Vector[Any]): Vector[Byte] = {
    gaussianRVs ++= as.asInstanceOf[Vector[Double]]
    assert(gaussianRVs.length >= filter.length)
    val result = gaussianRVs.sliding(filter.length).toArray.par map (filtered _)
    gaussianRVs = gaussianRVs takeRight (filter.length - 1)
    result.toVector
  }

}

object SimulatedValueSource {
  def props(
    seed: Long,
    mean: Double,
    stdDev: Double,
    filter: Seq[Double],
    bufferSize: Int = 1) =
      Props(classOf[SimulatedValueSource],
        seed,
        mean,
        stdDev,
        filter.toVector,
        bufferSize)
}
