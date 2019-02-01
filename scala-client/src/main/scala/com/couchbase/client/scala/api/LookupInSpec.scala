/*
 * Copyright (c) 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.scala.api

import scala.language.dynamics

sealed trait LookupOperation
case class GetOperation(path: String, xattr: Boolean) extends LookupOperation
case class GetFullDocumentOperation() extends LookupOperation
//case class GetStringOperation(path: String, xattr: Boolean) extends LookupOperation
//case class GetIntOperation(path: String, xattr: Boolean) extends LookupOperation
case class ExistsOperation(path: String, xattr: Boolean) extends LookupOperation
case class CountOperation(path: String, xattr: Boolean) extends LookupOperation
//case class WithExpiryOperation() extends LookupOperation

case class LookupInSpec(val operations: List[LookupOperation]) {
  def getDoc: LookupInSpec = {
    copy(operations = operations :+ GetFullDocumentOperation())
  }

  def getMulti(path: String*): LookupInSpec = {
    copy(operations = operations ++ path.map(v => GetOperation(v, false)))
  }

  def get(path: String, xattr: Boolean = false): LookupInSpec = {
    copy(operations = operations :+ GetOperation(path, xattr))
  }

//  def getString(path: String, xattr: Boolean = false): LookupInOps = {
//    copy(operations = operations :+ GetStringOperation(path, xattr))
//  }
//
//  def getInt(path: String, xattr: Boolean = false): LookupInOps = {
//    copy(operations = operations :+ GetIntOperation(path, xattr))
//  }

  def count(path: String, xattr: Boolean = false): LookupInSpec = {
    copy(operations = operations :+ CountOperation(path, xattr))
  }

  def exists(path: String, xattr: Boolean = false): LookupInSpec = {
    copy(operations = operations :+ ExistsOperation(path, xattr))
  }


//  def withExpiry: LookupInOps = {
//    copy(operations = operations :+ WithExpiryOperation())
//  }
}

object LookupInSpec {
  def apply() = new LookupInSpec(List.empty[LookupOperation])

  def empty = LookupInSpec(List())

  def getDoc: LookupInSpec = {
    empty.getDoc
  }

  def getMulti(path: String*): LookupInSpec = {
    empty.getMulti(path: _*)
  }

  def get(path: String, xattr: Boolean = false): LookupInSpec = {
    empty.get(path, xattr)
  }

  def count(path: String, xattr: Boolean = false): LookupInSpec = {
    empty.count(path, xattr)
  }

  def exists(path: String, xattr: Boolean = false): LookupInSpec = {
    empty.exists(path, xattr)
  }
}

//class SubDocument extends Dynamic {
//  def id: String = ???
//  def cas: Int = ???
//
//  def content(idx: Int): Any = null
//  def content(idx: String): Any = null
//
//  def contentAsArray: JsonArray = contentAs[JsonArray](null)
//  def contentAsObject: JsonObject = contentAs[JsonObject](null)
//
//  def contentAs[T]: T = contentAs(null)
//
//  def contentAs[T](path: String): T = contentAs(path, null)
//
//  def contentAs[T](path: String, decoder: (Array[Byte]) => T): T = None.asInstanceOf[T]
//
//  def selectDynamic(name: String): Any = content(name)
//
//}
//
//object SubDocument {
//  def unapplySeq(m: SubDocument): Option[Seq[Any]] = null
//}