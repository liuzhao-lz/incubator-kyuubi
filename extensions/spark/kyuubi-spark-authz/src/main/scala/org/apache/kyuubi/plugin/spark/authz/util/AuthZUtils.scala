/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.plugin.spark.authz.util

import scala.util.{Failure, Success, Try}

import org.apache.hadoop.security.UserGroupInformation
import org.apache.spark.{SPARK_VERSION, SparkContext}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.CatalogTable
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, View}
import org.apache.spark.sql.connector.catalog.{CatalogPlugin, Identifier, Table, TableCatalog}

private[authz] object AuthZUtils {

  /**
   * fixme error handling need improve here
   */
  def getFieldVal[T](o: Any, name: String): T = {
    Try {
      val field = o.getClass.getDeclaredField(name)
      field.setAccessible(true)
      field.get(o)
    } match {
      case Success(value) => value.asInstanceOf[T]
      case Failure(e) =>
        val candidates = o.getClass.getDeclaredFields.map(_.getName).mkString("[", ",", "]")
        throw new RuntimeException(s"$name not in ${o.getClass} $candidates", e)
    }
  }

  def getFieldValOpt[T](o: Any, name: String): Option[T] = Try(getFieldVal[T](o, name)).toOption

  def invoke(
      obj: AnyRef,
      methodName: String,
      args: (Class[_], AnyRef)*): AnyRef = {
    val (types, values) = args.unzip
    val method = obj.getClass.getMethod(methodName, types: _*)
    method.setAccessible(true)
    method.invoke(obj, values: _*)
  }

  def invokeStatic(
      obj: Class[_],
      methodName: String,
      args: (Class[_], AnyRef)*): AnyRef = {
    val (types, values) = args.unzip
    val method = obj.getMethod(methodName, types: _*)
    method.setAccessible(true)
    method.invoke(obj, values: _*)
  }

  /**
   * Get the active session user
   * @param spark spark context instance
   * @return the user name
   */
  def getAuthzUgi(spark: SparkContext): UserGroupInformation = {
    // kyuubi.session.user is only used by kyuubi
    val user = spark.getLocalProperty("kyuubi.session.user")
    if (user != null && user != UserGroupInformation.getCurrentUser.getShortUserName) {
      UserGroupInformation.createRemoteUser(user)
    } else {
      UserGroupInformation.getCurrentUser
    }
  }

  def hasResolvedHiveTable(plan: LogicalPlan): Boolean = {
    plan.nodeName == "HiveTableRelation" && plan.resolved
  }

  def getHiveTable(plan: LogicalPlan): CatalogTable = {
    getFieldVal[CatalogTable](plan, "tableMeta")
  }

  def extractTableOwner(table: CatalogTable): Option[String] = {
    Option(table.owner).filter(_.nonEmpty)
  }

  def hasResolvedDatasourceTable(plan: LogicalPlan): Boolean = {
    plan.nodeName == "LogicalRelation" && plan.resolved
  }

  def getDatasourceTable(plan: LogicalPlan): Option[CatalogTable] = {
    getFieldVal[Option[CatalogTable]](plan, "catalogTable")
  }

  def hasResolvedDatasourceV2Table(plan: LogicalPlan): Boolean = {
    plan.nodeName == "DataSourceV2Relation" && plan.resolved
  }

  def getDatasourceV2Identifier(plan: LogicalPlan): Option[Identifier] = {
    getFieldVal[Option[Identifier]](plan, "identifier")
  }

  def getDatasourceV2TableOwner(plan: LogicalPlan): Option[String] = {
    val table = getFieldVal[Table](plan, "table")
    Option(table.properties.get("owner"))
  }

  def getTableIdentifierFromV2Identifier(id: Identifier): TableIdentifier = {
    TableIdentifier(id.name(), Some(quote(id.namespace())))
  }

  def getTableOwnerFromV2Plan(
      child: Any,
      identifier: Identifier,
      catalogField: String = "catalog"): Option[String] = {
    getFieldVal[CatalogPlugin](child, catalogField) match {
      case tableCatalog: TableCatalog =>
        val table = tableCatalog.loadTable(identifier)
        Option(table.properties().get("owner"))
      case _ => None
    }
  }

  def hasResolvedPermanentView(plan: LogicalPlan): Boolean = {
    plan match {
      case view: View if view.resolved && isSparkVersionAtLeast("3.1.0") =>
        !getFieldVal[Boolean](view, "isTempView")
      case _ =>
        false
    }
  }

  def isSparkVersionAtMost(targetVersionString: String): Boolean = {
    SemanticVersion(SPARK_VERSION).isVersionAtMost(targetVersionString)
  }

  def isSparkVersionAtLeast(targetVersionString: String): Boolean = {
    SemanticVersion(SPARK_VERSION).isVersionAtLeast(targetVersionString)
  }

  def isSparkVersionEqualTo(targetVersionString: String): Boolean = {
    SemanticVersion(SPARK_VERSION).isVersionEqualTo(targetVersionString)
  }

  /**
   * check if spark version satisfied
   * first param is option of supported most  spark version,
   * and secont param is option of supported least spark version
   *
   * @return
   */
  def passSparkVersionCheck: (Option[String], Option[String]) => Boolean =
    (mostSparkVersion, leastSparkVersion) => {
      mostSparkVersion.forall(isSparkVersionAtMost) &&
      leastSparkVersion.forall(isSparkVersionAtLeast)
    }

  def quoteIfNeeded(part: String): String = {
    if (part.matches("[a-zA-Z0-9_]+") && !part.matches("\\d+")) {
      part
    } else {
      s"`${part.replace("`", "``")}`"
    }
  }

  def quote(parts: Seq[String]): String = {
    parts.map(quoteIfNeeded).mkString(".")
  }
}
