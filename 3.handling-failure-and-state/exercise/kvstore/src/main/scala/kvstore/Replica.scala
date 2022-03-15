package kvstore

import akka.actor.SupervisorStrategy.{Escalate, Restart, Stop}
import akka.actor.{Actor, ActorKilledException, ActorRef, Cancellable, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated, Timers, actorRef2Scala}
import kvstore.Arbiter.*
import akka.pattern.{ask, pipe}

import scala.concurrent.duration.*
import akka.util.Timeout
import kvstore.Replica.GetResult

import scala.concurrent.duration
import scala.language.postfixOps
import scala.util.Random

object Replica:
  sealed trait Operation:
    def key: String
    def id: Long
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply
  case class TimePassed(sender: ActorRef, key: String, id: Long)

  case object RetryOutstandingPersistence

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(Replica(arbiter, persistenceProps))

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor with Timers:
  import Replica.*
  import Replicator.*
  import Persistence.*
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */


  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]

  // waitList to persist elements
  var toPersist = Map.empty[(String, Long), (ActorRef, Option[String])]
  // waitList to replicate
  var toReplicate = Map.empty[(String, Long), Set[ActorRef]]

  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  var pendingOps = Map.empty[Long, (ActorRef, Cancellable)]

  var removedActors = Set.empty[ActorRef]

  var expectedSeq = 0L

  val persistenceActor = context.actorOf(persistenceProps)
  context.watch(this.persistenceActor)
  timers.startTimerAtFixedRate("Retry Persistence", RetryOutstandingPersistence, 100.milliseconds)


  def retryOutstandingPersistence() =
    if(!toPersist.isEmpty)
      val head = toPersist.head
      persist(head._1._1, head._2._2, head._1._2, head._2._1)



  // create the persistence Actor
  arbiter ! Join

  def receive =
    case JoinedPrimary   => context.become( leader)
    case JoinedSecondary => context.become(replica)

  private def scheduleTimeout(id: Long) =
    val operationFailed: OperationFailed = OperationFailed(id)
    val cancellable: Cancellable = context.system.scheduler.scheduleOnce(1.second, context.sender(), operationFailed)
    pendingOps += id ->(context.sender(), cancellable)


  def lookup(key: String, id: Long): GetResult =
    val keyContain = kv contains key
    if(keyContain)
      GetResult(key, Some(kv(key)), id)
    else
      GetResult(key, None, id)

  def persist(key: String, value: Option[String], id: Long, sender: ActorRef): Unit =
    toPersist += ((key, id) -> (sender, value))
    persistenceActor ! Persist(key, value, id)

  def replicateToAllNodes(key: String, value: Option[String], id: Long): Unit =
    toReplicate += ((key, id) -> (secondaries.values.toSet))
    secondaries.values.foreach {
      _ ! Replicate(key, value, id)
    }

  val leader: Receive =
    case Replicated(key, id) =>
      // exclude from toReplicate the current one that ended the process
      // exclude from toReplicate to wait the one that left the cluster
      if(!removedActors.contains(context.sender()))
          toReplicate += ((key, id) -> (toReplicate((key, id)).excl(context.sender()) -- removedActors))

          if (
            toReplicate((key, id)).isEmpty
              &&
              !toPersist.contains((key, id))
          )
            // remove the scheduler one second if successful
            if(pendingOps contains id)
              val (requester, timeout) = pendingOps(id)
              timeout.cancel()
              pendingOps -= id
            if(toPersist.contains((key, id)))
              val sender = toPersist(key, id)._1
              toPersist -= (key, id)
              toReplicate -= (key, id)
              sender ! OperationAck(id)

    case Replicas(replicas) =>
      val added = replicas -- secondaries.keySet - self
      val removed = secondaries.keySet -- replicas
      removedActors = removed

      removed foreach {
            replica => {
              secondaries(replica) ! PoisonPill
            }
        }
      // removes removed replicas
      secondaries = secondaries.filter((secondary,  replicator) => !removed.contains(secondary))

      added foreach {
          replica => {
            val replicator: ActorRef = context.actorOf(Replicator.props(replica))
            secondaries += replica -> replicator
            replicators += replicator

          }

      }
      // send to the new replica snapshot ( the others will ignore it if they already have it)
      kv foreach {
        case (key, value) => replicateToAllNodes(key, Some(value), Random.nextLong)
      }

    case Insert(key, value, id) =>
        // Persist
        persist(key, Some(value), id, context.sender())

        // Replicate to ALL the nodes
        replicateToAllNodes(key, Some(value), id)

        kv += key -> value

        // schedule one second to wait the responses from the other actors
        scheduleTimeout(id)

    case Remove(key, id) =>
        // Persist
        persist(key, None, id, context.sender())

        // Replicate to ALL the nodes
        replicateToAllNodes(key, None, id)

        kv -= key

        // schedule one second to wait the responses from the other actors
        scheduleTimeout(id)

    case Get(key, id) =>
        context.sender() ! lookup(key, id)

    case Persisted(key, id) =>
      val sender = toPersist((key, id))._1

      if (toReplicate((key, id)).isEmpty)
         toPersist -= (key, id)
        // remove the scheduler one second if successful
         if(pendingOps contains id)
            val (requester, timeout) = pendingOps(id)
            timeout.cancel()
            pendingOps -= id
         sender ! OperationAck(id)



    case RetryOutstandingPersistence => retryOutstandingPersistence()



  val replica: Receive =

    case Get(key, id) =>
        context.sender() ! lookup(key, id)

    case Snapshot(key, valueOption, seq) =>
        if(seq < expectedSeq)
          expectedSeq = Math.max(expectedSeq, seq + 1)
          context.sender() ! SnapshotAck(key, seq)
        if(seq == expectedSeq)
          valueOption match
            case Some(x) => kv += key -> x
            case None    => kv -= key
          expectedSeq = Math.max(expectedSeq, seq + 1)
          persist(key, valueOption, seq, context.sender())




    case Persisted(key, seq) =>
        if(toPersist.contains(key, seq))
          val sender = toPersist(key, seq)._1
          toPersist -= (key, seq)
          sender ! SnapshotAck(key, seq)


    case RetryOutstandingPersistence => retryOutstandingPersistence()




