package edu.nrao.vlite

import akka.actor._
import akka.remote.RemoteScope
import scala.concurrent.duration._

class Controller extends Actor with ActorLogging {
  import context._

  val settings = Settings(context.system)

  protected def emulatorActor(instance: EmulatorInstance, index: Int):
      ActorRef =
    actorOf(Emulator.props(
      device = instance.device,
      destination = instance.destination,
      hostname = instance.hostname,
      transport = instance.transport,
      framing = instance.framing,
      sourceIDs = instance.threadIDs map (tid => (instance.stationID, tid)),
      pace = instance.pace,
      decimation = instance.decimation,
      arraySize = instance.arraySize).
      withDeploy(Deploy(
        scope = RemoteScope(settings.remoteAddress(instance.hostname)))),
      s"emulator${index}")

  val emulators: Map[Int, EmulatorInfo] =
    (settings.emulatorInstances.zipWithIndex map {
      case (em, index) =>
        (index, EmulatorInfo(em, emulatorActor(em, index)))
    }).toMap

  protected def findEmulator(ref: ActorRef): Int = {
    (emulators find {
      case (_, EmulatorInfo(_, r)) if r == ref => true
      case _ => false
    } map {
      case (idx, _) => idx
    }).get
  }

  protected def foreachEmulator(f: ActorRef => Unit) {
    emulators foreach {
      case (_, EmulatorInfo(_, act)) => f(act)
    }
  }

  override def preStart() {
    println(s"Start ${self.path}")
    system.scheduler.schedule(
      1.second,
      1.second,
      self,
      Controller.TriggerDebug)
  }

  def receive: Receive = {
    case Controller.TriggerDebug =>
      foreachEmulator { _ ! Emulator.GetGeneratorLatencies }
      foreachEmulator { _ ! Transporter.GetBufferCount }
    case latencies: Emulator.Latencies =>
      log.info("{}", latencies)
    case count: Transporter.BufferCount =>
      log.info("emulator{} {}", findEmulator(sender), count)
  }
}

object Controller {
  case object TriggerDebug
  case object GetEmulatorRefs
}

case class EmulatorInfo(
  instance: EmulatorInstance,
  actorRef: ActorRef)
