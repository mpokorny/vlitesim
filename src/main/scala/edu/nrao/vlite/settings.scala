package edu.nrao.vlite

import scala.concurrent.duration._
import akka.actor._
import com.typesafe.config.{ Config, ConfigObject }
import akka.remote.RemoteScope

case class EmulatorInstance(
  index: Int,
  hostname: String,
  stationID: Int,
  threadIDs: List[Int],
  device: String,
  frameRate: Int,
  pace: FiniteDuration,
  destination: MAC)

class SettingsImpl(config: Config) extends Extension {
  import scala.collection.JavaConversions._
  val emulatorInstances =
    config.getObjectList("emulators.instances").toList.zipWithIndex map {
      case (obj: ConfigObject, index) => {
        val confObj = obj.toConfig
        EmulatorInstance(
          index = index,
          hostname = confObj.getString("hostname"),
          stationID = confObj.getString("stationID").getBytes.take(2) match {
            case Array(first, second) => (first.toInt << 8) + second.toInt
          },
          threadIDs = confObj.getIntList("threadIDs").toList.map(_.toInt),
          device = confObj.getString("device"),
          frameRate = confObj.getInt("frame-rate"),
          pace = confObj.getDuration("pace", MILLISECONDS).millis,
          destination = MAC(confObj.getString("destination")))
      }
    }
  val controllerHostname = config.getString("controller.hostname")

  val transport = config.getStringList("akka.remote.enabled-transports")(0)

  val protocol = config.getString(s"${transport}.transport-protocol")

  val port = config.getInt(s"${transport}.port")

  val hostname = config.getString(s"${transport}.hostname")

  def remoteAddress(host: String): Address =
    Address(protocol, "vlite", host, port)

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
