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

import scala.util.Random
import akka.actor.Props

object GaussianValueSource {
  def props(mean: Double, stdDev: Double, seed: Long, bufferSize: Int = 1):
      Props = {
    val rng = new Random(seed)
    ValueSource.props(() => rng.nextGaussian() * stdDev + mean, bufferSize)
  }
}
