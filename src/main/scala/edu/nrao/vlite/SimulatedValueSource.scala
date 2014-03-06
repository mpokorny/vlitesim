package edu.nrao.vlite

import scala.collection.mutable
import akka.actor._

class SimulatedValueSource(
  seeds: Seq[Long],
  val mean: Double,
  val stdDev: Double,
  val filter: List[Double],
  val bufferSize: Int) extends ValueSourceBase[Byte] {

  import context._

  val gaussianSource = actorOf(GaussianValueSource.props(mean, stdDev, seeds))

  val gaussianRVs = mutable.Queue[Double]()
  
  def requestValue() {
    gaussianSource ! ValueSource.Get
  }

  def receiveValue(a: Any): List[Byte] = {
    gaussianRVs.enqueue(a.asInstanceOf[Double])
    if (gaussianRVs.length == filter.length) {
      val d = ((0.0, filter) /: gaussianRVs.toList) {
        case ((acc, c :: cs), rv) => (acc + c * rv, cs)
      } match {
        case (sum, _) => sum
      }
      gaussianRVs.dequeue()
      List(((d max 0.0) min 255.0).toByte)
    } else {
      Nil
    }
  }
}

object SimulatedValueSource {
  def props(
    seeds: Seq[Long],
    mean: Double,
    stdDev: Double,
    filter: Seq[Double],
    bufferSize: Int = 1) =
      Props(classOf[SimulatedValueSource],
        seeds,
        mean,
        stdDev,
        filter.toList,
        bufferSize)
}
