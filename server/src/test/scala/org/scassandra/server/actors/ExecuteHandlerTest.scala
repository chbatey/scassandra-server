/*
 * Copyright (C) 2016 Christopher Batey and Dogan Narinc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.scassandra.server.actors

import java.util.concurrent.TimeUnit

import akka.io.Tcp.CloseCommand
import org.scassandra.server.actors.PrepareHandler.{PreparedStatementResponse, PreparedStatementQuery}
import org.scassandra.server.cqlmessages.request.ExecuteRequestV2
import org.scassandra.server.cqlmessages.response._
import org.scassandra.server.cqlmessages.types.{CqlVarchar, CqlBigint}

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestProbe, TestKitBase}
import akka.util.{ByteString, Timeout}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, Matchers, FunSuite}
import org.scassandra.server.cqlmessages._
import org.scassandra.server.priming._
import org.scassandra.server.priming.prepared.{PreparedPrime, PrimePreparedStore}
import org.scassandra.server.priming.query.{Prime, PrimeMatch}

class ExecuteHandlerTest extends FunSuite with Matchers with TestKitBase with BeforeAndAfter with MockitoSugar with ErrorHandlingBehaviors with FatalHandlingBehaviors {
  implicit lazy val system = ActorSystem()

  implicit val impProtocolVersion = VersionTwo
  var underTest: ActorRef = null
  var testProbeForTcpConnection: TestProbe = _
  var prepareHandlerTestProbe: TestProbe = _
  val versionTwoMessageFactory = VersionTwoMessageFactory
  val protocolByte: Byte = ProtocolVersion.ServerProtocolVersionTwo
  val activityLog: ActivityLog = new ActivityLog
  val primePreparedStore = mock[PrimePreparedStore]
  val stream: Byte = 0x3

  implicit val atMost: Duration = 1 seconds
  implicit val timeout: Timeout = 1 seconds

  before {
    reset(primePreparedStore)
    when(primePreparedStore.findPrime(any(classOf[PrimeMatch]))).thenReturn(None)
    testProbeForTcpConnection = TestProbe()
    prepareHandlerTestProbe = TestProbe()
    underTest = TestActorRef(new ExecuteHandler(primePreparedStore, activityLog, prepareHandlerTestProbe.ref))
  }

  test("Should return empty result message for execute - no params") {
    val stream: Byte = 0x02
    val id: Int = 1
    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, id).serialize().drop(8)

    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(id)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map(id -> "Some query")))
    testProbeForTcpConnection.expectMsg(VoidResult(stream))
  }

  test("Should look up prepared prime in store with consistency & query") {
    val stream: Byte = 0x02
    val query = "select * from something where name = ?"
    val id = 1
    val consistency = THREE

    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, id, consistency).serialize().drop(8)
    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(id)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map(id -> query)))
    verify(primePreparedStore).findPrime(PrimeMatch(query, consistency))
  }

  test("Should create rows message if prime matches") {
    val stream: Byte = 0x02
    val query = "select * from something where name = ?"
    val id = 1
    val primeMatch = Some(PreparedPrime())
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, 1).serialize().drop(8)
    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(id)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map(id -> query)))
    testProbeForTcpConnection.expectMsg(Rows("" ,"" ,stream, Map(), List()))
  }

  test("Should record execution in activity log") {
    activityLog.clearPreparedStatementExecutions()
    val stream: Byte = 0x02
    val query = "select * from something where name = ?"
    val preparedStatementId = 1
    val consistency = TWO
    val variableTypes = List(CqlBigint)
    val variables: List[Int] = List(10)
    val primeMatch = Some(PreparedPrime(variableTypes))
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, preparedStatementId, consistency, 1, variables, variableTypes = variableTypes).serialize().drop(8)
    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(preparedStatementId)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map(preparedStatementId -> query)))
    activityLog.retrievePreparedStatementExecutions().size should equal(1)
    activityLog.retrievePreparedStatementExecutions().head should equal(PreparedStatementExecution(query, consistency, variables.map(Some(_)), variableTypes))
  }

  test("Should record execution in activity log without variables when variables don't match prime") {
    activityLog.clearPreparedStatementExecutions()
    val stream: Byte = 0x02
    val query = "select * from something where name = ? and something = ?"
    val preparedStatementId = 1
    val consistency = TWO
    val variableTypes = List(CqlBigint, CqlBigint)
    val variables: List[Int] = List(10, 20)
    val primeMatch = Some(PreparedPrime(List(CqlVarchar))) // prime has a single variable
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, preparedStatementId, consistency, 1, variables, variableTypes = variableTypes).serialize().drop(8)
    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(preparedStatementId)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map(preparedStatementId -> query)))
    activityLog.retrievePreparedStatementExecutions().size should equal(1)
    activityLog.retrievePreparedStatementExecutions().head should equal(PreparedStatementExecution(query, consistency, List(), List()))
  }

  test("Should record execution in activity log event if not primed") {
    activityLog.clearPreparedStatementExecutions()
    val stream: Byte = 0x02
    val query = "select * from something where name = ?"
    val preparedStatementId = 1
    val consistency = TWO
    val variableTypes = List(CqlBigint)
    val variables: List[Int] = List(10)
    val primeMatch = Some(PreparedPrime(variableTypes))
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(None)

    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, preparedStatementId, consistency, 1, variables, variableTypes = variableTypes).serialize().drop(8)
    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(preparedStatementId)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map(preparedStatementId -> query)))
    activityLog.retrievePreparedStatementExecutions().size should equal(1)
    activityLog.retrievePreparedStatementExecutions().head should equal(PreparedStatementExecution(query, consistency, List(), List()))

  }

  test("Should return unprepared response if not prepared statement not found") {
    activityLog.clearPreparedStatementExecutions()
    val stream: Byte = 0x02
    val preparedStatementId = 8675
    val errMsg = "Could not find prepared statement with id: 0x000021e3"
    val consistency = TWO
    val variableTypes = List(CqlBigint)
    val variables: List[Int] = List(10)

    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, preparedStatementId, consistency, 1, variables, variableTypes = variableTypes).serialize().drop(8)
    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(preparedStatementId)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map()))
    activityLog.retrievePreparedStatementExecutions().size should equal(1)
    activityLog.retrievePreparedStatementExecutions().head should equal(PreparedStatementExecution(errMsg, consistency, List(), List()))

    testProbeForTcpConnection.expectMsgPF() {
      case Unprepared(`stream`, `errMsg`, _) => true
    }
  }

  test("Should delay message if fixedDelay primed") {
    val stream: Byte = 0x02
    val query = "select * from something where name = ?"
    val preparedStatementId = 1
    val prime = Prime(fixedDelay = Some(FiniteDuration(1500, TimeUnit.MILLISECONDS)))
    val primeMatch = Some(PreparedPrime(prime = prime))
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, preparedStatementId).serialize().drop(8)
    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    // i wish that expect msg took a min as well as a max :(
    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(preparedStatementId)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map(preparedStatementId -> query)))
    testProbeForTcpConnection.expectMsg(Rows("" ,"" ,stream, Map(), List()))
  }

  override def executeWithError(result: ErrorResult, expectedError: (Byte, Consistency) => Error): Unit = {
    val query = "select * from something where name = ?"
    val consistency = QUORUM
    val preparedStatementId = 1
    val primeMatch = Some(PreparedPrime(prime = Prime(result = result)))
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, preparedStatementId, consistency = consistency).serialize().drop(8)
    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(preparedStatementId)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map(preparedStatementId -> query)))
    testProbeForTcpConnection.expectMsg(expectedError(stream, consistency))
  }

  override def executeWithFatal(result: FatalResult, expectedCommand: CloseCommand): Unit = {
    val query = "select * from something where name = ?"
    val consistency = QUORUM
    val preparedStatementId = 1
    val primeMatch = Some(PreparedPrime(prime = Prime(result = result)))
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequestV2(protocolByte, stream, preparedStatementId, consistency = consistency).serialize().drop(8)
    underTest ! ExecuteHandler.Execute(executeBody, stream, versionTwoMessageFactory, testProbeForTcpConnection.ref)

    prepareHandlerTestProbe.expectMsg(PreparedStatementQuery(List(preparedStatementId)))
    prepareHandlerTestProbe.reply(PreparedStatementResponse(Map(preparedStatementId -> query)))
    testProbeForTcpConnection.expectMsg(expectedCommand)
  }
}
