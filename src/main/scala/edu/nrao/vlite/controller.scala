package edu.nrao.vlite

import akka.actor._
import scala.concurrent.duration._

class Controller extends Actor with ActorLogging {
  import context._

  val settings = Settings(context.system)

  val emulatorSelections: Map[Int, (String, Int, ActorSelection)] =
    (settings.emulatorInstances.groupBy (_.hostname) map {
      case (hostname, ems) =>
        ems.zipWithIndex map {
          case (em, idx) =>
            (em.index,
              (hostname,
                idx,
                actorSelection(settings.remotePath(hostname, s"emulator$idx"))))
        }
    }).flatten.toMap

  var emulatorRefs: Map[Int, (Option[ActorRef], Boolean)] = Map(
    (for (i <- emulatorSelections.keys)
    yield i -> (None, true)).toList:_*)

  protected def currentEmulatorRefs =
    emulatorRefs.values.withFilter(_._1.isDefined).map(_._1.get)

  protected def haveAllEmulatorRefs = emulatorRefs.values.forall(_._1.isDefined)

  protected def startEmulator(index: Int) {
    if (emulatorRefs contains index) {
      emulatorRefs(index) match {
        case (optRef, _) =>
          optRef foreach (_ ! Emulator.Start)
          emulatorRefs = emulatorRefs.updated(index, (optRef, true))
      }
    }
  }

  protected def stopEmulator(index: Int) {
    if (emulatorRefs contains index) {
      emulatorRefs(index) match {
        case (optRef, _) =>
          optRef foreach (_ ! Emulator.Stop)
          emulatorRefs = emulatorRefs.updated(index, (optRef, false))
      }
    }
  }

  protected def findEmulatorIndex(hostname: String, index: Int) =
    (emulatorSelections find {
      case (_, (hostname, index, _)) => true
      case _ => false
    }).get._1

  var getIds: Option[Cancellable] =
    Some(system.scheduler.schedule(
      0.second,
      1.second,
      self,
      Controller.GetIdentities))

  system.scheduler.schedule(1.second, 1.second, self, Controller.TriggerDebug)

  def receive: Receive = {
    case Controller.GetIdentities =>
      emulatorRefs foreach {
        case (idx, (None, _)) =>
          emulatorSelections(idx) match {
            case (_, _, sel) => sel ! Identify(idx)
          }
        case _ =>
      }
    case ActorIdentity(idx: Int, Some(ref)) =>
      val isStarted = emulatorRefs(idx)._2
      emulatorRefs = emulatorRefs.updated(idx, (Some(ref), isStarted))
      if (isStarted) ref ! Emulator.Start
      if (haveAllEmulatorRefs) {
        getIds.foreach(_.cancel)
        getIds = None
      }
    case _: ActorIdentity =>
    case Controller.Shutdown =>
      system.shutdown
    case Controller.StartAll =>
      (0 until emulatorRefs.size) foreach (i => startEmulator(i))
    case Controller.StopAll =>
      (0 until emulatorRefs.size) foreach (i => stopEmulator(i))
    case Controller.StartOne(index) =>
      startEmulator(index)
    case Controller.StopOne(index) =>
      stopEmulator(index)
    case Controller.StartOneOnHost(hostname, index) =>
      startEmulator(findEmulatorIndex(hostname, index))
    case Controller.StopOneOnHost(hostname, index) =>
      stopEmulator(findEmulatorIndex(hostname, index))
    case Controller.TriggerDebug =>
      currentEmulatorRefs foreach { _ ! Emulator.GetGeneratorLatencies }
      currentEmulatorRefs foreach { _ ! Transporter.GetBufferCount }
    case msg =>
      log.info(msg.toString)
  }
}

object Controller {
  case object Shutdown
  case object StartAll
  case object StopAll
  case class StartOne(index: Int)
  case class StopOne(index: Int)
  case class StartOneOnHost(hostname: String, index: Int)
  case class StopOneOnHost(hostname: String, index: Int)
  case object TriggerDebug
  case object GetIdentities
}
