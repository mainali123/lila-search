package lila.search

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.fields.ElasticField
import com.sksamuel.elastic4s.{ ElasticClient, ElasticDsl, Index => ESIndex, Response }
import scala.concurrent.{ ExecutionContext, Future }

case class JsonObject(json: String) extends AnyVal

case class Index(name: String) extends AnyVal {
  def toES: ESIndex = ESIndex(name)
}

final class ESClient(client: ElasticClient)(implicit ec: ExecutionContext) {

  private def toResult[A](response: Response[A]): Future[A] =
    response.fold[Future[A]](Future.failed(new Exception(response.error.reason)))(Future.successful)

  def search[A](index: Index, query: A, from: From, size: Size)(implicit q: Queryable[A]) =
    client execute {
      q.searchDef(query)(from, size)(index)
    } flatMap toResult map SearchResponse.apply

  def count[A](index: Index, query: A)(implicit q: Queryable[A]) =
    client execute {
      q.countDef(query)(index)
    } flatMap toResult map CountResponse.apply

  def store(index: Index, id: Id, obj: JsonObject) =
    client execute {
      indexInto(index.name) source obj.json id id.value
    }

  def storeBulk(index: Index, objs: List[(String, JsonObject)]) =
    if (objs.isEmpty) funit
    else
      client execute {
        ElasticDsl.bulk {
          objs.map { case (id, obj) =>
            indexInto(index.name) source obj.json id id
          }
        }
      }

  def deleteOne(index: Index, id: Id) =
    client execute {
      deleteById(index.toES, id.value)
    }

  def deleteMany(index: Index, ids: List[Id]) =
    client execute {
      ElasticDsl.bulk {
        ids.map { id =>
          deleteById(index.toES, id.value)
        }
      }
    }

  def putMapping(index: Index, fields: Seq[ElasticField]) =
    dropIndex(index) >> client.execute {
      createIndex(index.name).mapping(
        properties(fields) source false // all false
      ) shards 5 replicas 0 refreshInterval Which.refreshInterval(index)
    }

  def refreshIndex(index: Index) =
    client
      .execute {
        ElasticDsl refreshIndex index.name
      }
      .void
      .recover { case _: Exception =>
        println(s"Failed to refresh index $index")
      }

  private def dropIndex(index: Index) =
    client.execute {
      deleteIndex(index.name)
    }
}