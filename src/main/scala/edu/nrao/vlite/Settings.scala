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

import scala.concurrent.duration._
import akka.actor._
import com.typesafe.config.{ Config, ConfigObject, ConfigException }
import akka.remote.RemoteScope
import java.net.{ InetAddress, InetSocketAddress }

case class EmulatorInstance(
  hostname: String,
  stationID: Int,
  threadIDs: List[Int],
  pace: FiniteDuration,
  transport: Emulator.Transport.Transport,
  framing: Option[EthernetTransporter.Framing.Framing],
  device: Option[String],
  decimation: Int,
  arraySize: Int,
  destination: (String, String),
  genParams: Option[GeneratorParams])

class SettingsImpl(config: Config) extends Extension {
  import scala.collection.JavaConversions._
  val emulatorInstances =
    config.getObjectList("emulators.instances").toList.zipWithIndex map {
      case (obj: ConfigObject, index) => {
        val instanceConf = obj.toConfig
        val transportStr = instanceConf.getString("transport")
        val transport =
          if (transportStr == "ethernet")
            Emulator.Transport.Ethernet
          else if (transportStr == "udp")
            Emulator.Transport.UDP
          else
            throw new ConfigException.BadValue(
              s"emulators.instances($index).transport",
              "value must be either 'ethernet' or 'udp'")
        def destinationSock = {
          val hostname =
            instanceConf.getString("destination.udp.hostname")
          val port = instanceConf.getInt("destination.udp.port")
          s"$hostname:$port"
        }
        val (device, destination, framing) = transport match {
          case Emulator.Transport.Ethernet =>
            val (sock, framing) = instanceConf.getString("vdif-framing") match {
              case "raw" =>
                ("", EthernetTransporter.Framing.Raw)
              case "udp" =>
                (destinationSock, EthernetTransporter.Framing.UDP)
            }
            (Some(instanceConf.getString("device")),
              (sock, instanceConf.getString("destination.ethernet")),
              Some(framing))
          case Emulator.Transport.UDP =>
            (None, (destinationSock, ""), None)
        }
        val hostname = instanceConf.getString("hostname")
        val genParams =
          instanceConf.getString("array-data-content") match {
            case "zero" =>
              None
            case "simulated" =>
              val params =
                instanceConf.getObject("simulated-array-data-params").toConfig
              Some(SimParams(
                seed = params.getLong("seed"),
                filter = params.getDoubleList("filter").map(_.toDouble).toVector,
                scale = params.getDouble("scale"),
                offset = params.getLong("offset")))
            case "file" =>
              val params =
                instanceConf.getObject("file-array-data-params").toConfig
              Some(FileParams(
                readBufferSize = params.getInt("read-buffer-size"),
                fileNamePattern = params.getString("file-name-pattern")))
          }
        EmulatorInstance(
          hostname = hostname,
          stationID = instanceConf.getInt("stationID"),
          threadIDs = instanceConf.getIntList("threadIDs").toList.map(_.toInt),
          pace = instanceConf.getDuration("pace", MILLISECONDS).millis,
          transport = transport,
          framing = framing,
          device = device,
          decimation = instanceConf.getInt("decimation"),
          arraySize = instanceConf.getInt("vdif-data-size"),
          destination = destination,
          genParams = genParams)
      }
    }
  val controllerHostname = config.getString("controller.hostname")

  val transport = config.getStringList("akka.remote.enabled-transports")(0)

  val protocol = config.getString(s"${transport}.transport-protocol")

  val port = config.getInt(s"${transport}.port")

  val hostname = config.getString(s"${transport}.hostname")

  def remoteAddress(host: String): Address =
    Address(s"akka.$protocol", "vlite", host, port)

  def remotePath(host: String, name: String): String =
    "akka." + remoteAddress(host).toString + s"/user/$name"
}

object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {

  override def lookup = Settings

  override def createExtension(system: ExtendedActorSystem) =
    new SettingsImpl(system.settings.config)

  /**
    *  Java API: retrieve the Settings extension for the given system.
    */
  override def get(system: ActorSystem): SettingsImpl = super.get(system)
}
