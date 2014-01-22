package edu.nrao.vlite

import akka.actor._
import akka.remote.RemoteScope
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }

class Controller extends Actor with ActorLogging {
  import context._

  val settings = Settings(context.system)

  override def supervisorStrategy = {
    import akka.actor.SupervisorStrategy._

    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 1.minute) {
      case _ => Restart
    }
  }

  protected def emulatorActor(instance: EmulatorInstance, index: Int):
      Option[ActorRef] =
    Try(actorOf(Emulator.props(
      device = instance.device,
      destination = instance.destination,
      sourceIDs = instance.threadIDs map (tid => (instance.stationID, tid)),
      pace = instance.pace,
      decimation = instance.decimation).
      withDeploy(Deploy(
        scope = RemoteScope(settings.remoteAddress(instance.hostname)))),
      s"emulator${index}")).toOption

  var emulators: Map[Int, EmulatorInfo] = Map.empty

  protected def haveAllEmulatorRefs =
    emulators.values.forall(_.optActorRef.isDefined)

  protected def currentEmulators = emulators.values withFilter {
    case EmulatorInfo(_, Some(_), _) => true
    case _ => false
  } map (_.optActorRef.get)


  protected def changeEmulatorState(index: Int, newState: Boolean) {
    if (emulators contains index) {
      val ei = emulators(index)
      ei.optActorRef foreach { ref =>
        ref ! (if (newState) Emulator.Start else Emulator.Stop)
      }
      emulators = emulators.updated(
        index,
        EmulatorInfo(ei.instance, ei.optActorRef, newState))
    }
  }
  
  protected def startEmulator(index: Int) {
    changeEmulatorState(index, true)
  }

  protected def stopEmulator(index: Int) {
    changeEmulatorState(index, false)
  }

  protected def findEmulator(ref: ActorRef): Option[Int] = {
    emulators find {
      case (_, EmulatorInfo(_, Some(r), _)) if r == ref => true
      case _ => false
    } map {
      case (idx, _) => idx
    }
  }

  var getRefs: Option[Cancellable] = None

  override def preStart() {
    emulators = (settings.emulatorInstances.zipWithIndex map {
      case (em, index) =>
        (index, EmulatorInfo(em, emulatorActor(em, index), true))
    }).toMap
    currentEmulators foreach { _ ! Emulator.Start }
    getRefs = Some(system.scheduler.schedule(
      0.second,
      1.second,
      self,
      Controller.GetEmulatorRefs))
    system.scheduler.schedule(
      1.second,
      1.second,
      self,
      Controller.TriggerDebug)
  }

  def receive: Receive = {
    case Controller.GetEmulatorRefs =>
      emulators = emulators map {
        case (index, EmulatorInfo(em, None, state)) =>
          val ref = emulatorActor(em, index)
          ref foreach { act =>
            act ! (if (state) Emulator.Start else Emulator.Stop)
          }
          (index, EmulatorInfo(em, ref, state))
        case other =>
          other
      }
      if (haveAllEmulatorRefs) {
        getRefs map (_.cancel)
        getRefs = None
      }
    case Controller.Shutdown =>
      system.shutdown
    case Controller.StartAll =>
      (0 until emulators.size) foreach (i => startEmulator(i))
    case Controller.StopAll =>
      (0 until emulators.size) foreach (i => stopEmulator(i))
    case Controller.StartOne(index) =>
      startEmulator(index)
    case Controller.StopOne(index) =>
      stopEmulator(index)
    case Controller.TriggerDebug =>
      currentEmulators foreach { _ ! Emulator.GetGeneratorLatencies }
      currentEmulators foreach { _ ! Transporter.GetBufferCount }
    case latencies: Emulator.Latencies =>
      log.info(latencies.toString)
    case count: Transporter.BufferCount =>
      findEmulator(sender) foreach { idx =>
        log.info("emulator{} {}", idx, count)
      }
  }
}

object Controller {
  case object Shutdown
  case object StartAll
  case object StopAll
  case class StartOne(index: Int)
  case class StopOne(index: Int)
  case object TriggerDebug
  case object GetEmulatorRefs
}

case class EmulatorInfo(
  instance: EmulatorInstance,
  optActorRef: Option[ActorRef],
  isStarted: Boolean)
