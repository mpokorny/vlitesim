package edu.nrao.vlite

import akka.actor._
import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory

class Simulator extends Bootable {

  val system = ActorSystem("vlite", ConfigFactory.load.getConfig("vlite"))

  def startup {
    val settings = Settings(system)

    if (settings.hostname == settings.controllerHostname)
      system.actorOf(Props[Controller], "controller")

    settings.emulatorInstances.filter(_.hostname == settings.hostname).
      zipWithIndex foreach {
        case (instance, index) =>
          system.actorOf(
            Emulator.props(
              instance.device,
              instance.destination,
              instance.threadIDs map (tid => (instance.stationID, tid)),
              instance.pace,
              instance.decimation),
            s"emulator${index}")
      }
  }

  def shutdown {
    system.shutdown
  }
}
