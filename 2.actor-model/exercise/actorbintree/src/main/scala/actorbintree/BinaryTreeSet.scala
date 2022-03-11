/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor.*
import scala.collection.immutable.Queue

object BinaryTreeSet:

  trait Operation:
    def requester: ActorRef
    def id: Int
    def elem: Int

  trait OperationReply:
    def id: Int

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection */
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply



class BinaryTreeSet extends Actor:
  import BinaryTreeSet.*
  import BinaryTreeNode.*

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional (used to stash incoming operations during garbage collection)
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  /** Those are the operations that the user can perform */
  val normal: Receive =
    case operation: Operation => root ! operation
    case GC =>
        val newRoot = createRoot
        root ! CopyTo(newRoot)
        context.become(garbageCollecting(newRoot))


  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive =
    case operation: Operation => pendingQueue = pendingQueue.enqueue(operation)

    case CopyFinished =>
        root ! PoisonPill
        root = newRoot
      // unstash all elements in the pending queue
        pendingQueue.map(root ! _)
        pendingQueue = Queue.empty
        context.become(normal)






object BinaryTreeNode:
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  /**
   * Acknowledges that a copy has been completed. This message should be sent
   * from a node to its parent, when this node and all its children nodes have
   * finished being copied.
   */
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor:
  import BinaryTreeNode.*
  import BinaryTreeSet.*

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved


  // optional
  def receive = normal

  def childToVisit(elemToFind: Int): Position =
    if (elemToFind > elem) Right
    else Left

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive =

    case Insert(requester, id, messageElem) =>
      // case element is contained in this node, send back the result
      if (messageElem == elem)
        // if the current node is logically removed, restore it.
        removed = false
        requester ! OperationFinished(id)

      else
        val child = childToVisit(messageElem)

        // check if the node left/right exists , if yes, send a message there, otherwise create one.
        if (subtrees.contains(child))
          subtrees(child) ! Insert(requester, id, messageElem)
        else
          subtrees += (child -> context.actorOf(BinaryTreeNode.props(messageElem, false)))
          requester ! OperationFinished(id)


    case Remove(requester, id, messageElem) =>
      // case element is contained in this node, logically remove it
      if (messageElem == elem)
        removed = true
        requester ! OperationFinished(id)

      // case received elem greater than the current elem
      else
        val child = childToVisit(messageElem)
        // check if the node on the left/ right exists, if yes, send a message there, otherwise send OperationFinished (not Found).
        if (subtrees.contains(child))
          subtrees(child) ! Remove(requester, id, messageElem)
        else
          requester! OperationFinished(id)


    case Contains(requester, id, messageElem) =>

      // case element is contained in this node and not removed, send back the true result, otherwise false.
      if (messageElem == elem)
        requester ! ContainsResult(id, !removed)

      else
        val child = childToVisit(messageElem)
        // check if the node on the left/Right exists, if yes, send a message there, otherwise send not Found.
        if (subtrees.contains(child))
          subtrees(child) ! Contains(requester, id, messageElem)
        else
          requester ! ContainsResult(id, false)



    case CopyTo(newRoot) =>
      // If is not removed, re insert the element in the new root.
      if (!removed) newRoot ! Insert(self, elem, elem)
      //NOTE

      if (removed && subtrees.isEmpty)
        context.sender() ! CopyFinished
      else
      // Now the context is the one of copying, to when Insert will send a message confirming that the element was inserted, copying will manage it/
      // the fact that is removed = true, doesn't mean that we can't receive messages from the children, we are still in the process of copying
        context.become(copying(subtrees.values.toSet, insertConfirmed = removed))

      // Copy the children and apply the same procedure, in case are not removed are re-inserted.
      subtrees.values.foreach(_ ! CopyTo(newRoot))


      // optional

      /** `expected` is the set of ActorRefs whose replies we are waiting for,
        * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
        */
      def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive =
          case OperationFinished(_) =>
            // if the  element to insert is a removed one, we just wait that expected will become empty, our job is done, we send copy finished
              if (expected.isEmpty)
                context.parent ! CopyFinished
                context.become(normal) // Switch back to normal for future
              else
                context.become(copying(expected, insertConfirmed = true))
          case CopyFinished =>
              val newExpected = expected - context.sender()
              if (newExpected.isEmpty && insertConfirmed)
                context.parent ! CopyFinished
                context.become(normal)
              else
                context.become(copying(newExpected, insertConfirmed))





