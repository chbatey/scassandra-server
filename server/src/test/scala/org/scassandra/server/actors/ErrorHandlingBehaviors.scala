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

import org.scalatest.FunSuite
import org.scassandra.server.cqlmessages.{ProtocolProvider, Consistency}
import org.scassandra.server.cqlmessages.response._
import org.scassandra.server.priming._

trait ErrorHandlingBehaviors extends ProtocolProvider {
  this: FunSuite =>

  def executeWithError(result: ErrorResult, expectedError: (Byte, Consistency) => Error): Unit

  test("Execute with read time out") {
    val result = ReadRequestTimeoutResult()
    executeWithError(result, (b, c) => ReadRequestTimeout(b, c, result))
  }

  test("Execute with write time out") {
    val result = WriteRequestTimeoutResult()
    executeWithError(result, (b, c) => WriteRequestTimeout(b, c, result))
  }

  test("Execute with unavailable") {
    val result: UnavailableResult = UnavailableResult()
    executeWithError(result, (b, c) => UnavailableException(b, c, result))
  }

  test("Execute with server error") {
    val message = "Server Error"
    executeWithError(ServerErrorResult(message), (b, c) => ServerError(b, message))
  }

  test("Execute with protocol error") {
    val message = "Protocol Error"
    executeWithError(ProtocolErrorResult(message), (b, c) => ProtocolError(b, message))
  }

  test("Execute with bad credentials") {
    val message = "Bad Credentials"
    executeWithError(BadCredentialsResult(message), (b, c) => BadCredentials(b, message))
  }

  test("Execute with overloaded") {
    val message = "Overloaded"
    executeWithError(OverloadedResult(message), (b, c) => Overloaded(b, message))
  }

  test("Execute with is bootstrapping") {
    val message = "I'm bootstrapping"
    executeWithError(IsBootstrappingResult(message), (b, c) => IsBootstrapping(b, message))
  }

  test("Execute with truncate error") {
    val message = "Truncate Error"
    executeWithError(TruncateErrorResult(message), (b, c) => TruncateError(b, message))
  }

  test("Execute with syntax error") {
    val message = "Syntax Error"
    executeWithError(SyntaxErrorResult(message), (b, c) => SyntaxError(b, message))
  }

  test("Execute with invalid") {
    val message = "Invalid"
    executeWithError(InvalidResult(message), (b, c) => Invalid(b, message))
  }

  test("Execute with config error") {
    val message = "Config Error"
    executeWithError(ConfigErrorResult(message), (b, c) => ConfigError(b, message))
  }

  test("Execute with already exists") {
    val message = "Already Exists"
    val keyspace = "keyspace"
    val table = "table"
    executeWithError(AlreadyExistsResult(message, keyspace, table),  (b, c) => AlreadyExists(b, message, keyspace, table))
  }

  test("Execute with unprepared") {
    val message = "Unprepared"
    val id: Array[Byte] = Array[Byte](0x00, 0x12, 0x13, 0x14)
    executeWithError(UnpreparedResult(message, id), (b, c) => Unprepared(b, message, id))
  }
}

