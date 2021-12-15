package com.klibisz.elastiknn.query

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.klibisz.elastiknn.api.ElasticsearchCodec._
import com.klibisz.elastiknn.api._
import com.klibisz.elastiknn.{ElastiknnNearestNeighborsQueryBuilder, api4j}
import com.klibisz.elastiknn.api4j.ElastiknnNearestNeighborsQuery
import com.klibisz.elastiknn.api4j.ElastiknnNearestNeighborsQuery.CosineLsh
import com.klibisz.elastiknn.testing.ElasticAsyncClient
import com.sksamuel.elastic4s.ElasticDsl._
import org.apache.http.HttpHost
import org.apache.lucene.document.Document
import org.elasticsearch.client.RestClient
import org.elasticsearch.xcontent.json.JsonXContent
import org.elasticsearch.xcontent.{ToXContent, XContentBuilder}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import scala.concurrent.Future
import scala.util.Random

class JavaClientSuite extends AsyncFunSuite with Matchers with ElasticAsyncClient {

  implicit val rng = new Random(0)

  test("Java client smoketest") {
    val (index, field, id) = ("java-client-smoketest", "vec", "id")
    val corpus = Vec.DenseFloat.randoms(100, 1000)
    val ids = corpus.indices.map(i => s"v$i")
    val mapping = Mapping.L2Lsh(corpus.head.dims, 50, 1, 2)

    val javaClient = new ElasticsearchClient(new RestClientTransport(
      RestClient.builder(new HttpHost("localhost", 9200, "http")).build(), new JacksonJsonpMapper()
    ))
    val query = new ElastiknnNearestNeighborsQuery.L2Lsh(new api4j.Vector.DenseFloat(corpus.head.values), 20, 2)
    val queryBuilder = new ElastiknnNearestNeighborsQueryBuilder(query, field)
    queryBuilder.to
    val searchRequest = SearchRequest.of(r => r.query())

    for {
      _ <- deleteIfExists(index)
      _ <- eknn.createIndex(index)
      _ <- eknn.putMapping(index, field, id, mapping)
      _ <- eknn.index(index, field, corpus, id, ids)
      _ <- eknn.execute(refreshIndex(index))

    } yield {
      val javaClientResult = javaClient.search(searchRequest, classOf[Document])
      val hits = javaClientResult.hits().hits()
      hits.size() shouldBe 10
      hits.get(0).id() shouldBe "v0"
    }
  }

  test("XContent codec matches Scala codec") {

    val dfv = Vec.DenseFloat.random(10)
    val sbv = Vec.SparseBool.random(20)

    val cases = Seq(
      new ElastiknnNearestNeighborsQuery.Exact(new api4j.Vector.DenseFloat(dfv.values), api4j.Similarity.L1) ->
        NearestNeighborsQuery.Exact("vec", Similarity.L1, dfv),
      new ElastiknnNearestNeighborsQuery.Exact(new api4j.Vector.DenseFloat(dfv.values), api4j.Similarity.L2) ->
        NearestNeighborsQuery.Exact("vec", Similarity.L2, dfv),
      new ElastiknnNearestNeighborsQuery.Exact(new api4j.Vector.DenseFloat(dfv.values), api4j.Similarity.COSINE) ->
        NearestNeighborsQuery.Exact("vec", Similarity.Cosine, dfv),
      new ElastiknnNearestNeighborsQuery.Exact(new api4j.Vector.SparseBool(sbv.trueIndices, sbv.totalIndices), api4j.Similarity.JACCARD) ->
        NearestNeighborsQuery.Exact("vec", Similarity.Jaccard, sbv),
      new ElastiknnNearestNeighborsQuery.L2Lsh(new api4j.Vector.DenseFloat(dfv.values), 22, 3) ->
        NearestNeighborsQuery.L2Lsh("vec", 22, 3, dfv),
      new CosineLsh(new api4j.Vector.DenseFloat(dfv.values), 22) ->
        NearestNeighborsQuery.CosineLsh("vec", 22, dfv),
      new ElastiknnNearestNeighborsQuery.PermutationLsh(new api4j.Vector.DenseFloat(dfv.values), api4j.Similarity.COSINE, 22) ->
        NearestNeighborsQuery.PermutationLsh("vec", Similarity.Cosine, 22, dfv),
      new ElastiknnNearestNeighborsQuery.PermutationLsh(new api4j.Vector.DenseFloat(dfv.values), api4j.Similarity.L2, 22) ->
        NearestNeighborsQuery.PermutationLsh("vec", Similarity.L2, 22, dfv)
    )

    // Encode the java query via XContent, decode it via Circe, compare it to the Scala query.
    val checked = cases.zipWithIndex.map {
      case ((javaQuery, scalaQuery), i) =>
        val bos = new ByteArrayOutputStream()
        val xcb = new XContentBuilder(JsonXContent.jsonXContent, bos)
        val qb = new ElastiknnNearestNeighborsQueryBuilder(javaQuery, scalaQuery.field)
        qb.toXContent(xcb, ToXContent.EMPTY_PARAMS)
        xcb.flush()
        val qbJsonString = bos.toString()
        val qbJsonParsed = parse(qbJsonString).map(_ \\ "elastiknn_nearest_neighbors").flatMap(_.head.as[NearestNeighborsQuery])
        info(s"case $i: ${scalaQuery.withVec(Vec.Empty())}")
        withClue(s"case $i:") {
          qbJsonParsed shouldBe Right(scalaQuery)
        }
    }

    Future(checked.last)
  }

}
