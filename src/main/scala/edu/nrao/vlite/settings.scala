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
  decimation: Int,
  pace: FiniteDuration,
  transport: Emulator.Transport.Transport,
  framing: Option[EthernetTransporter.Framing.Framing],
  device: Option[String],
  destination: (String, String))

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
        EmulatorInstance(
          hostname = instanceConf.getString("hostname"),
          stationID =
            instanceConf.getString("stationID").getBytes.take(2) match {
              case Array(first, second) => (first.toInt << 8) + second.toInt
            },
          threadIDs = instanceConf.getIntList("threadIDs").toList.map(_.toInt),
          decimation = instanceConf.getInt("decimation"),
          pace = instanceConf.getDuration("pace", MILLISECONDS).millis,
          transport = transport,
          framing = framing,
          device = device,
          destination = destination)
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

  val vdifArraySize = config.getInt("vdif.array-size")
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
