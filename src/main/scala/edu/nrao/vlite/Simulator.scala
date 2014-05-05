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
import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory

class Simulator extends Bootable {

  val version = "1.3"

  val system = ActorSystem("vlite", ConfigFactory.load.getConfig("vlite"))

  def startup() {
    println(s"Starting VLITE simulator (v. $version)...")

    val settings = Settings(system)

    if (settings.hostname == settings.controllerHostname)
      system.actorOf(Props[Controller], "controller")
  }

  def shutdown() {
    system.shutdown()
    system.awaitTermination()
    println("Stopped VLITE simulator")
  }
}
