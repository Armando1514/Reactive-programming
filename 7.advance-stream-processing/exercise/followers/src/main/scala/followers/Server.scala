package followers

import akka.NotUsed
import akka.event.Logging
import akka.stream.scaladsl.{BroadcastHub, Flow, Framing, Keep, MergeHub, Sink, Source}
import akka.stream.{ActorAttributes, Materializer}
import akka.util.ByteString
import followers.model.{Event, Followers, Identity}

import scala.collection.immutable.{Queue, SortedSet}
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Utility object that describe stream manipulations used by the server
  * implementation.
  */
object Server extends ServerModuleInterface:

  /**
    * A flow that consumes chunks of bytes and produces `String` messages.
    *
    * Each incoming chunk of bytes doesn’t necessarily contain ''exactly one'' message
    * payload (it can contain fragments of payloads only). You have to process these
    * chunks to produce ''frames'' containing exactly one message payload.
    *
    * Messages are delimited by the '\n' character.
    *
    * If the last frame does not end with a delimiter, this flow should fail the
    * stream instead of returning a truncated frame.
    *
    * Hint: you may find the [[Framing]] flows useful.
    */
  val reframedFlow: Flow[ByteString, String, NotUsed] =
    Framing
      .delimiter(ByteString("\n"), maximumFrameLength = 999, allowTruncation = false)
      .map(_.utf8String)

  /**
    * A flow that consumes chunks of bytes and produces [[Event]] messages.
    *
    * Each incoming chunk of bytes doesn't necessarily contain exactly one message payload (it
    * can contain fragments of payloads only). You have to process these chunks to produce
    * frames containing exactly one message payload before you can parse such messages with
    * [[Event.parse]].
    *
    * Hint: reuse `reframedFlow`
    */
  val eventParserFlow: Flow[ByteString, Event, NotUsed] =
    reframedFlow
      .map(Event.parse(_))

  /**
    * Implement a Sink that will look for the first [[Identity]]
    * (we expect there will be only one), from a stream of commands and materializes a Future with it.
    *
    * Subsequent values once we found an identity can be ignored.
    *
    * Note that you may need the Sink's materialized value; you may
    * want to compare the signatures of `Flow.to` and `Flow.toMat`
    * (and have a look at `Keep.right`).
    */
  val identityParserSink: Sink[ByteString, Future[Identity]] =
    reframedFlow
      .map(Identity.parse(_))
      .toMat(Sink.head)(Keep.right)

  /**
    * A flow that consumes unordered messages and produces messages ordered by `sequenceNr`.
    *
    * User clients expect to be notified of events in the correct order, regardless of the order in which the
    * event source sent them.
    *
    * You will have to buffer messages with a higher sequence number than the next one
    * expected to be produced. The first message to produce has a sequence number of 1.
    *
    * You may want to use `statefulMapConcat` in order to keep the state needed for this
    * operation around in the operator.
    */
  val reintroduceOrdering: Flow[Event, Event, NotUsed] =
    Flow[Event].statefulMapConcat(() => {

      var nextSeqNumber = 1;
      var reorderingBuffer = Map[Int, Event]()

      def extractOrderedSequence(expectedSequenceNr: Int, orderedSequence: Queue[Event]) : Queue[Event] =
        if (reorderingBuffer.contains(expectedSequenceNr)) {
          val nextEvent = reorderingBuffer(expectedSequenceNr)
          reorderingBuffer -= expectedSequenceNr
          nextSeqNumber = expectedSequenceNr + 1
          extractOrderedSequence(expectedSequenceNr + 1, orderedSequence.enqueue(nextEvent))
        } else {
          orderedSequence
        }

      event => {
        reorderingBuffer += (event.sequenceNr -> event)
        extractOrderedSequence(nextSeqNumber, Queue.empty)
      }
    })

  /**
    * A flow that associates a state of [[Followers]] to
    * each incoming [[Event]].
    *
    * Hints:
    *  - start with a state where nobody follows nobody,
    *  - you may find the `statefulMapConcat` operation useful.
    */
  val followersFlow: Flow[Event, (Event, Followers), NotUsed] =

      Flow[Event].statefulMapConcat {
        () =>
          var followers: Followers = Map.empty.withDefaultValue(Set.empty)
          event => {
            event match {
              case Event.Follow(sequenceNr, fromUserId, toUserId) =>
                val newFollowers = followers(fromUserId) + toUserId
                followers = followers.updated(fromUserId, newFollowers)
              case Event.Unfollow(sequenceNr, fromUserId, toUserId) =>
                val newFollowers = followers(fromUserId) - toUserId
                followers = followers.updated(fromUserId, newFollowers)
              case _ =>
            }

            (event, followers) :: Nil
          }
      }


  /**
    * @return Whether the given user should be notified by the incoming `Event`,
    *         given the current state of `Followers`. See [[Event]] for more
    *         information of when users should be notified about them.
    * @param userId Id of the user
    * @param eventAndFollowers Event and current state of followers
    */
  def isNotified(userId: Int)(eventAndFollowers: (Event, Followers)): Boolean =
    eventAndFollowers match {
      case (Event.Follow(_, _, toUserId), _) => userId == toUserId
      case (Event.Unfollow(_, _, _), _) => false
      case (Event.PrivateMsg(_, _, toUserId), _) => userId == toUserId
      case (Event.Broadcast(_), _) => true
      case (Event.StatusUpdate(_, fromUserId), followers: Followers) => followers.getOrElse(userId, Set.empty).contains(fromUserId)
    }

  // Utilities to temporarily have unimplemented parts of the program
  private def unimplementedFlow[A, B, C]: Flow[A, B, C] =
    Flow.fromFunction[A, B](_ => ???).mapMaterializedValue(_ => ??? : C)

  private def unimplementedSink[A, B]: Sink[A, B] = Sink.ignore.mapMaterializedValue(_ => ??? : B)

/**
  * Creates a hub accepting several client connections and a single event connection.
  *
  * @param executionContext Execution context for `Future` values transformations
  * @param materializer Stream materializer
  */
class Server(using ExecutionContext, Materializer)
  extends ServerInterface with ExtraStreamOps:
  import Server.*

  val (inboundSink, broadcastOut) =

    val flow: Flow[ByteString, (Event, Followers), NotUsed] =
      eventParserFlow
        .via(reintroduceOrdering)
        .via(followersFlow)
    /**
      * A flow that consumes the event source, re-frames it,
      * decodes the events, re-orders them, and builds a Map of
      * followers at each point it time. It produces a stream
      * of the decoded events associated with the current state
      * of the followers Map.
      */


    // Wires the MergeHub and the BroadcastHub together and runs the graph
    MergeHub.source[ByteString](256)
      .via(flow)
      .toMat(BroadcastHub.sink(256))(Keep.both)
      .withAttributes(ActorAttributes.logLevels(Logging.DebugLevel, Logging.DebugLevel, Logging.DebugLevel))
      .run()

  /**
    * The "final form" of the event flow.
    *
    * It consumes byte strings which are the events, and feeds them to the hub inbound.
    *
    * The server does not need to write any data back to the event source (use
    * `Source.maybe` to represent something that does not write data, yet at the same
    * time does NOT complete the stream, otherwise the connection could be closed).
    *
    * Note that you still want the connection to be closed when the event source
    * is completed. Compare the documentation of `Flow.fromSinkAndSource` and
    * `Flow.fromSinkAndSourceCoupled` to find how to achieve that.
    */
  val eventsFlow: Flow[ByteString, Nothing, NotUsed] =
    Flow.fromSinkAndSourceCoupled(inboundSink, Source.maybe[Nothing])

  /**
    * @return The source of events for the given user
    * @param userId Id of the user
    *
    * Reminder on delivery semantics of messages:
    *
    * Follow:          Only the To User Id should be notified
    * Unfollow:        No clients should be notified
    * Broadcast:       All connected user clients should be notified
    * Private Message: Only the To User Id should be notified
    * Status Update:   All current followers of the From User ID should be notified
    */
  def outgoingFlow(userId: Int): Source[ByteString, NotUsed] =
    broadcastOut.collect {
      case (e@Event.Follow(_, _, to), _) if to == userId => e.render
      case (e@Event.Broadcast(_), _) => e.render
      case (e@Event.PrivateMsg(_, _, to), _) if to == userId => e.render
      case (e@Event.StatusUpdate(_, from), followers) if followers.getOrElse(userId, Set.empty).contains(from) => e.render
    }

  /**
   * The "final form" of the client flow.
   *
   * Clients will connect to this server and send their id as an Identity message (e.g. "21323\n").
   *
   * The server should establish a link from the event source towards the clients, in such way that they
   * receive only the events that they are interested about.
   *
   * The incoming side of this flow needs to extract the client id to then properly construct the outgoing Source,
   * as it will need this identifier to notify the server which data it is interested about.
   *
   * Hints:
   *   - since the clientId will be emitted as a materialized value of `identityParserSink`,
   *     you may need to use mapMaterializedValue to side effect it into a shared Promise/Future that the Source
   *     side can utilise to construct such Source "from that client id future".
   *   - Remember to use `via()` to connect a `Flow`, and `to()` to connect a `Sink`.
   */
  def clientFlow(): Flow[ByteString, ByteString, NotUsed] =
    val clientIdPromise = Promise[Identity]()
//    clientIdPromise.future.map(id => actorSystem.log.info("Connected follower: {}", id.userId))

    // A sink that parses the client identity and completes `clientIdPromise` with it
    val incoming: Sink[ByteString, NotUsed] =
      identityParserSink.mapMaterializedValue { fut =>
        fut.foreach(clientIdPromise.success)
        NotUsed
      }

    val outgoing = Source.futureSource(clientIdPromise.future.map { identity =>
      outgoingFlow(identity.userId)
    })

    Flow.fromSinkAndSource(incoming, outgoing)

