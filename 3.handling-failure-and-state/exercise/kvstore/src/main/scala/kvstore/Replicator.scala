package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.actorRef2Scala
import scala.concurrent.duration.*

object Replicator:
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(Replicator(replica))

class Replicator(val replica: ActorRef) extends Actor:
  import Replicator.*
  import context.dispatcher
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]
  
  var _seqCounter = 0L
  def nextSeq() =
    val ret = _seqCounter
    _seqCounter += 1
    ret


  def receive: Receive =
    case Replicate(key: String, valueOption: Option[String], id: Long) =>
      val seq = nextSeq()
      acks += seq -> (context.sender(), Replicate(key, valueOption, id))
      replica ! Snapshot(key, valueOption, seq)

    case SnapshotAck(key, seq) =>
      acks.get(seq).map { (sender, request) =>
        sender ! Replicated(key, request.id)
        acks -= seq

      }


  def resend = {
    acks foreach {case (seq,(_,Replicate(key,valuop,_))) => replica ! Snapshot(key,valuop,seq)}
  }
/*
For grading purposes it is assumed that this happens roughly every 100  milliseconds.
To allow for batching (see above) we will assume that a
lost Snapshot message will lead to a resend at most 200 milliseconds
after the Replicate request was received
(again, the ActorSystem’s scheduler service is considered precise
enough for this purpose).
*/
  override def preStart(): Unit= {
    context.system.scheduler.schedule(0.milliseconds, 100.milliseconds)(resend)
  }