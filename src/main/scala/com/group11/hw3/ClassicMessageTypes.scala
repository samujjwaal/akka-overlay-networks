package com.group11.hw3

import akka.actor.ActorRef
import com.google.gson.JsonObject

import scala.collection.mutable


case class CReadKey(key: String)
case class CWriteValue(key: String, value: String)


case class CNodeAdaptedResponse()
case class CGetNodeIndex()
case class CDisplayNodeInfo()
//case class FindSuccessor(key: String) extends NodeCommand
case class CFindNode(node: ActorRef)

case class CGetFingerTableStatus()
case class CFingerTableStatusResponse(ft: String)

case class CUpdateFingerTable(nodeRef:ActorRef,nodeId:BigInt,i:Int,key:BigInt)

case class CGetKeyValue(key: String)
case class CWriteKeyValue(key: String, value: String)
case class CDataResponse(message:String)

case class CJoinNetwork(networkRef: ActorRef)
case class CJoinStatus(status: String)

case class CFindKeyPredecessor(key: BigInt)
case class CFindKeyPredResponse(predId: BigInt, predRef: ActorRef)

case class CFindKeySuccessor(key: BigInt)
case class CFindKeySuccResponse(succId: BigInt,succRef: ActorRef, predId: BigInt, predRef: ActorRef)

case class CGetNodePredecessor(key: BigInt)
case class CSetNodePredecessor(nodeId: BigInt,nodeRef: ActorRef)

case class CSetNodeSuccessor(nodeId: BigInt,nodeRef: ActorRef)
case class CGetNodeSuccessor()
case class CGetNodeSuccResponse(nodeId: BigInt, nodeRef: ActorRef)

case class CCallFindPredecessor(ref: ActorRef,key: BigInt)
case class CCallFindPredResponse(predId: BigInt, predRef: ActorRef)

case class CGetNodeSnapshot(ref:ActorRef)
case class CGetNodeSnapshotResponse(snap:JsonObject)

case class CreateNodes()
case class CaptureGlobalSnapshot()

case class CreateUsers()
case class StartUserRequests()

case class CreateNodesReply(nodeHash:mutable.HashMap[BigInt, ActorRef])

case class CUpdateFingerTables()
case class CWriteInitialData()
case class CCaptureGlobalSnapshot()
