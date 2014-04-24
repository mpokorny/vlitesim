//
// Copyright © 2014 Associated Universities, Inc. Washington DC, USA.
//
// This file is part of vlitesim.
//
// vlitesim is free software: you can redistribute it and/or modify it under the
// terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version.
//
// vlitesim is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// vlitesim.  If not, see <http://www.gnu.org/licenses/>.
//
package edu.nrao.vlite

import akka.actor._

class SimulatedValueSource(
  seed: Long,
  val mean: Double,
  val stdDev: Double,
  val filter: Vector[Double],
  val bufferSize: Int) extends ValueSourceBase[Byte] {

  import context._

  val gaussianSource =
    actorOf(GaussianValueSource.props(mean, stdDev, seed), "gaussian")

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
