package com.group11.hw3.chord

import scala.concurrent.duration._
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.sharding.ShardRegion
import akka.util.Timeout
import akka.pattern.{ask, pipe}
import com.group11.hw3._
import com.typesafe.config.Config

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.util.control.Breaks._

object ChordClassicNode {

  def props(nodeHash:BigInt):Props= {
    Props(new ChordClassicNode())
  }
  val numberOfShards=100
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id.toString, payload)
  }


  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _)       => (id % numberOfShards).toString
    case ShardRegion.StartEntity(id) =>
      // StartEntity is used by remembering entities feature
      (id.toLong % numberOfShards).toString
  }

}

class ChordClassicNode() extends Actor with ActorLogging{
//  implicit val timeout: Timeout = Timeout(10.seconds)
  implicit val ec: ExecutionContext = context.dispatcher
  val nodeHash : BigInt= BigInt(self.path.name)
  val nodeConf: Config = context.system.settings.config
  val ringSize: BigInt = BigInt(2).pow(nodeConf.getInt("networkConstants.M"))

  var poc: BigInt = _

  var predecessorId: BigInt = nodeHash
  var successorId: BigInt = nodeHash

  var nodeData = new mutable.HashMap[BigInt,Int]()
  val numFingers: Int = nodeConf.getInt("networkConstants.M")
  var fingerTable = new Array[ClassicFinger](numFingers)

  var shardRegion: ActorRef= null

  fingerTable.indices.foreach(i =>{
    val start:BigInt = (nodeHash + BigInt(2).pow(i)) % ringSize
    fingerTable(i) = ClassicFinger(start, nodeHash)
  } )

  /**
   * Check whether a given value lies within a range. This function also takes care of zero crossover.
   * @param leftInclude : Flag to indicate if left bound is to be included
   * @param leftValue : Left bound of the interval
   * @param rightValue : Right bound of the interval
   * @param rightInclude : Flag to indicate if left bound is to be included
   * @param valueToCheck : Value which is checked for its presence between the left and right bounds of interval
   * @return Boolean indicating presence or absence
   */
  def checkRange( leftInclude: Boolean,
                  leftValue: BigInt,
                  rightValue: BigInt,
                  rightInclude: Boolean,
                  valueToCheck: BigInt): Boolean = {
    if (leftValue == rightValue) {
      true
    }
    else if (leftValue < rightValue) {
      if ((valueToCheck == leftValue && leftInclude) || (valueToCheck == rightValue && rightInclude) || (valueToCheck > leftValue && valueToCheck < rightValue)) {
        true
      } else {
        false
      }
    } else {
      if ((valueToCheck == leftValue && leftInclude) || (valueToCheck == rightValue && rightInclude) || (valueToCheck > leftValue || valueToCheck < rightValue)) {
        true
      } else {
        false
      }
    }

  }

  /**
   * Update finger tables of all nodes in the network ofter a new node is added.
   */
  def updateFingerTablesOfOthers(): Unit = {
    val M=nodeConf.getInt("networkConstants.M")
    for (i <- 0 until M ) {
      val p = (nodeHash - BigInt(2).pow(i) + BigInt(2).pow(M) + 1) % BigInt(2).pow(M)
//      var (pred,predID)=findKeyPredecessor(p)
      shardRegion ! EntityEnvelope(successorId , CUpdateFingerTable(nodeHash,i,p))
    }
  }

  /**
   * Print finger table
   * @return fingerTableStatus : Returns a string representing current state of the finger table.
   */
  def getFingerTableStatus(): String = {
    var fingerTableStatus: String = "[ "
    for (finger <- fingerTable) {
      fingerTableStatus = fingerTableStatus+"( "+finger.start.toString+" : "+finger.nodeId.toString+" ), "
    }
    fingerTableStatus = fingerTableStatus + "]"
    fingerTableStatus
  }

  /**
   * Function to search the finger table to find a node which is the closest predecessor of given key
   * @param key : key hash whose predecessor is needed.
   * @return resPredRef : Ref of the predecessor found.
   *         resPredId  : Hash ID of predecessor found.
   */
  def findClosestPredInFT(key:BigInt):BigInt = {
    //var resPredRef: ActorRef = self
    var resPredId: BigInt = nodeHash

    // Assuming the key is not equal to my hash

    // Go through the finger table and find the closest finger preceding the key
    // Check if key is not found between any two fingers.
    // If not, it is beyond the last finger, hence the last finger is the closest predecessor.
    var found = false
    breakable {
      for (i <- 0 until numFingers - 1) {
        val curFingernode = fingerTable(i).nodeId
        val nextFingerNode = fingerTable(i + 1).nodeId
        // If key is between current finger node and next finger node, then return current finger node
        if (checkRange(false, curFingernode, nextFingerNode, true, key)) {
          //resPredRef = fingerTable(i).nodeRef
          resPredId = fingerTable(i).nodeId
          found = true
          break
        }
      }
    }

    // If key is beyond the last finger node, return the last finger node.
    if (!found) {
      resPredId = fingerTable(numFingers-1).nodeId
      //resPredRef = fingerTable(numFingers-1).nodeRef
    }

    // If no closest node found in the finger table, return self
    resPredId
  }

//  /**
//   * Find Predecessor of the given key.
//   * @param key : key hash whose predecessor is needed.
//   * @return resPredRef : Ref of the predecessor found.
//   *         resPredId  : Hash ID of predecessor found.
//   */
//  def findKeyPredecessor(key:BigInt): (ActorRef, BigInt) = {
//    var resPredRef: ActorRef = self
//    var resPredId: BigInt = nodeHash
//
//    // Check my immediate neighbors for the predecessor.
//    // If the key lies between my hash and my successor's hash, return myself
//    if (checkRange(leftInclude = false, nodeHash, successorId, rightInclude = true, key)) {
//      resPredRef = self
//      resPredId = nodeHash
//    }
//
//    // If the key lies between my hash and my predecessor's hash, return my predecessor
//    else if (checkRange(leftInclude = false, predecessorId, nodeHash, rightInclude = true, key)) {
//      resPredRef = predecessor
//      resPredId = predecessorId
//    }
//
//    // Predecessor not found in immediate neighbors.
//    // Find closest predecessor in finger table and forward the request.
//    else {
//      val (fingerNodeRef, fingerNodeId) = findClosestPredInFT(key)
//      implicit val timeout: Timeout = Timeout(10.seconds)
//      val future = fingerNodeRef ? CFindKeyPredecessor(key)
//      val predNode = Await.result(future, timeout.duration).asInstanceOf[CFindKeyPredResponse]
//      resPredRef = predNode.predRef
//      resPredId = predNode.predId
//    }
//    (resPredRef,resPredId)
//  }

  log.info("Classic actor created")
  override def receive: Receive = {

    case CJoinNetwork(chordShardRegion,peerID) => {
      // We assume network has at least one node and so, networkRef is not null

      log.info("Join network called for node "+nodeHash.toString)
      this.shardRegion=chordShardRegion
      if(peerID != nodeHash) {

        this.poc = peerID
        implicit val timeout: Timeout = Timeout(5.seconds)
        val future = shardRegion ? EntityEnvelope(poc , CGetNodeNeighbors(nodeHash))
        val nodeNbrResp = Await.result(future, timeout.duration).asInstanceOf[CGetNodeNeighborsResponse]

        //println("--- Finding succ --- Node :" + nodeHash.toString + " succ : " + nodeNbrResp.succId.toString)
        //fingerTable(0).nodeRef = nodeNbrResp.succRef
        fingerTable(0).nodeId = nodeNbrResp.succId
        //successor = nodeNbrResp.succRef
        successorId = nodeNbrResp.succId
        //predecessor = nodeNbrResp.predRef
        predecessorId = nodeNbrResp.predId

//        successor ! CSetNodePredecessor(nodeHash, self)
//        predecessor ! CSetNodeSuccessor(nodeHash, self)

        shardRegion ! EntityEnvelope(successorId, CSetNodePredecessor(nodeHash))
        shardRegion ! EntityEnvelope(predecessorId, CSetNodeSuccessor(nodeHash))

        for (i <- 1 until numFingers) {

          //println("updating finger " + i + " for node " + nodeHash.toString)
          val lastSucc = fingerTable(i - 1).nodeId
          val curStart = fingerTable(i).start

          if (checkRange(false, nodeHash, lastSucc, true, curStart)) {
            //println("finger carry over.")
            fingerTable(i).nodeId = fingerTable(i - 1).nodeId
            //fingerTable(i).nodeRef = fingerTable(i - 1).nodeRef
          }
          else {
            //println("finding new successor for finger. ")
            implicit val timeout: Timeout = Timeout(5.seconds)
            val future = shardRegion ? EntityEnvelope(poc , CGetNodeNeighbors(curStart))
            val nodeNbrResp = Await.result(future, timeout.duration).asInstanceOf[CGetNodeNeighborsResponse]
            //fingerTable(i).nodeRef = nodeNbrResp.succRef
            fingerTable(i).nodeId = nodeNbrResp.succId

          }
        }
        //println(getFingerTableStatus())
        updateFingerTablesOfOthers()
      }
      log.info("{} added to chord network", nodeHash)
      sender() ! CJoinStatus("Complete!")
    }

    case CGetNodeNeighbors(key) => {
      //var nodesuccRef: ActorRef = null
      var nodesuccId: BigInt = null
      //var nodepredRef: ActorRef = null
      var nodepredId: BigInt = null
      if (checkRange(false, nodeHash, successorId, true, key)) {
        //nodesuccRef = successor
        nodesuccId = successorId
        //nodepredRef = self
        nodepredId = nodeHash
        sender ! CGetNodeNeighborsResponse(nodesuccId,nodepredId)
      }
      else if (checkRange(false, predecessorId, nodeHash, true, key)) {
        //nodesuccRef = self
        nodesuccId = nodeHash
        //nodepredRef = predecessor
        nodepredId = predecessorId
        sender ! CGetNodeNeighborsResponse(nodesuccId,nodepredId)
      }
      else {
        val next_pocId = findClosestPredInFT(key)
        implicit val timeout: Timeout = Timeout(5.seconds)
        (shardRegion ? EntityEnvelope(next_pocId , CGetNodeNeighbors(key))).pipeTo(sender())
//        val future = next_pocRef ? CGetNodeNeighbors(key)
//        val nodeNbrResp = Await.result(future, timeout.duration).asInstanceOf[CGetNodeNeighborsResponse]
//        nodesuccRef = nodeNbrResp.succRef
//        nodesuccId = nodeNbrResp.succId
//        nodepredRef = nodeNbrResp.predRef
//        nodepredId = nodeNbrResp.predId
      }
//      sender ! CGetNodeNeighborsResponse(nodesuccId,nodesuccRef,nodepredId,nodepredRef)
    }

//    case CFindKeySuccessor(key) => {
//      var keysuccRef: ActorRef = null
//      var keysuccId: BigInt = null
//      var keypredRef: ActorRef = null
//      var keypredId: BigInt = null
//
//      if (key == nodeHash) {
//        keysuccRef = self
//        keysuccId = nodeHash
//        keypredId = predecessorId
//        keypredRef = predecessor
//      }
//      else {
//        val (ref, id) = findKeyPredecessor(key)
//        keypredRef = ref
//        keypredId = id
////        println("inside FindKeySuccessor. Got pred " + keypredId.toString)
//
//        if (keypredRef == self) {
//          // key is found between this node and it successor
//          keysuccRef = successor
//          keysuccId = successorId
//          keypredId = nodeHash
//          keypredRef = self
//        }
//        else {
//          // key is found between node keypred and its successor. Get keypred's successor and reply with their ref
//          implicit val timeout: Timeout = Timeout(10.seconds)
//          val future = keypredRef ? CGetNodeSuccessor()
//          val nodeSuccessorResponse = Await.result(future, timeout.duration).asInstanceOf[CGetNodeSuccResponse]
//          keysuccId = nodeSuccessorResponse.nodeId
//          keysuccRef = nodeSuccessorResponse.nodeRef
//        }
//      }
//      sender ! CFindKeySuccResponse(keysuccId,keysuccRef,keypredId,keypredRef)
//    }
//
//    case CFindKeyPredecessor(key) => {
//      val (predRef, predId) = findKeyPredecessor(key)
//      // println("inside FindKeyPredecessor. got "+predId.toString)
//      sender ! CFindKeyPredResponse(predId, predRef)
//    }

    case CGetNodeSuccessor() => {
      sender ! CGetNodeSuccResponse(successorId)
    }

    case CSetNodeSuccessor(id) => {
      //successor = ref
      successorId = id
      //fingerTable(0).nodeRef = ref
      fingerTable(0).nodeId = id
    }

    case CSetNodePredecessor(id) => {
      //predecessor = ref
      predecessorId = id
    }

    case CUpdateFingerTable(id,i,pos) => {
      if (id != nodeHash) {
        if (checkRange(leftInclude = false, nodeHash, fingerTable(0).nodeId, rightInclude = true, pos)) {
          if (checkRange(leftInclude = false, nodeHash, fingerTable(i).nodeId, rightInclude = false, id)) {
            //fingerTable(i).nodeRef = ref
            fingerTable(i).nodeId = id
            shardRegion ! EntityEnvelope(predecessorId , CUpdateFingerTable( id,i,nodeHash))
          }
        } else {
          val nextPredId = findClosestPredInFT(pos)
          shardRegion ! EntityEnvelope(nextPredId , CUpdateFingerTable( id,i,pos))
        }
      }
    }

    case CGetFingerTableStatus() => {
      sender ! CFingerTableStatusResponse(getFingerTableStatus())
    }

    case CGetKeyValue(key: Int) => {
      //println("Dummy value for " + key)
      log.info("Received read request for : "+nodeHash)
      if (checkRange(leftInclude = false, predecessorId, nodeHash, rightInclude = true, key)) {
        if (nodeData.contains(key)) {
          sender ! CDataResponse(nodeData(key).toString)
        } else {
          sender ! CDataResponse("Key not found!")
        }
      }
      else {
        if (checkRange(leftInclude = false, nodeHash, fingerTable(0).nodeId, rightInclude = true, key)) {
          implicit val timeout: Timeout = Timeout(10.seconds)
          (shardRegion ? EntityEnvelope(fingerTable(0).nodeId , CGetValueFromNode(key))).pipeTo(sender())
//          val future = fingerTable(0).nodeRef ? CGetValueFromNode(key)
//          val keyValue = Await.result(future, timeout.duration).asInstanceOf[CDataResponse]
//          sender ! CDataResponse(keyValue.message)
        } else {
          implicit val timeout: Timeout = Timeout(10.seconds)
          val target = findClosestPredInFT(key)
          (shardRegion ? EntityEnvelope(target , CGetKeyValue(key))).pipeTo(sender())
//          val future = target ? CGetKeyValue(key)
//          val keyValue = Await.result(future, timeout.duration).asInstanceOf[CDataResponse]
//          sender ! CDataResponse(keyValue.message)
        }
      }
    }

    case CGetValueFromNode(key: Int) => {

      if (nodeData.contains(key)) {
        sender ! CDataResponse(nodeData(key).toString)
      } else {
        sender ! CDataResponse("Data not found")
      }
    }

//    case CWriteKeyValue(key: String, value: String) => {
//      //println("Received write request by classic chord node actor for:" + key + "," + value)
//      log.info("Received write request by classic chord node actor for : " + key + "," + value)
//    }

    case CWriteKeyValue(key: BigInt, value: Int) => {

      //log.info("Received write request for:"+nodeHash)
      if (checkRange(leftInclude = false, nodeHash, fingerTable(0).nodeId, rightInclude = true, key)) {
        //println("!!!Found node"+nodeHash +"for key:"+key)
        shardRegion ! EntityEnvelope(fingerTable(0).nodeId , CWriteDataToNode(key, value))
      } else {
        val target = findClosestPredInFT(key)
        //println("!!!Forwarding to node"+target +"for key:"+key)
        shardRegion ! EntityEnvelope(target , CWriteKeyValue(key, value))
      }
    }

    case CWriteDataToNode(key: BigInt, value: Int) => {
      log.info("Key %s  should be owned owned by node %s.".format(key, self.path.name))
      if (nodeData.contains(key)) {
        nodeData.update(key, value)
      } else {
        nodeData.addOne(key, value)
      }
    }

    case _ => log.info("Chord node actor recieved a generic message.")

  }

}
