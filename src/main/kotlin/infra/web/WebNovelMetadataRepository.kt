package infra.web

import com.jillesvangurp.jsondsl.JsonDsl
import com.jillesvangurp.ktsearch.*
import com.jillesvangurp.searchdsls.querydsl.*
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.result.UpdateResult
import infra.ElasticSearchDataSource
import infra.MongoDataSource
import infra.WebNovelFavoriteModel
import infra.WebNovelMetadataEsModel
import infra.model.*
import infra.provider.RemoteNovelListItem
import infra.provider.WebNovelProviderDataSource
import infra.provider.providers.Hameln
import infra.provider.providers.Syosetu
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.id.toId
import util.Optional
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*

object WebNovelFilter {
    enum class Type { 全部, 连载中, 已完结, 短篇 }
    enum class Level { 全部, 一般向, R18 }
    enum class Translate { 全部, AI }
}

private fun Enum<*>.serialName(): String =
    javaClass.getDeclaredField(name).getAnnotation(SerialName::class.java)!!.value

class WebNovelMetadataRepository(
    private val provider: WebNovelProviderDataSource,
    private val mongo: MongoDataSource,
    private val es: ElasticSearchDataSource,
) {
    suspend fun listRank(
        providerId: String,
        options: Map<String, String>,
    ): Result<List<WebNovelMetadataOutline>> {
        return provider
            .listRank(providerId, options)
            .map { rank ->
                rank.map { remote ->
                    val local = mongo.webNovelMetadataCollection
                        .findOne(WebNovelMetadata.byId(providerId, remote.novelId))
                    remote.toOutline(providerId, local)
                }
            }
    }

    suspend fun search(
        userQuery: String?,
        filterProvider: String?,
        filterType: WebNovelFilter.Type,
        filterLevel: WebNovelFilter.Level,
        filterTranslate: WebNovelFilter.Translate,
        page: Int,
        pageSize: Int,
    ): Page<WebNovelMetadataOutline> {
        val response = es.client.search(
            ElasticSearchDataSource.webNovelIndexName,
            from = page * pageSize,
            size = pageSize
        ) {
            query = bool {
                val mustQueries = mutableListOf<ESQuery>()
                val mustNotQueries = mutableListOf<ESQuery>()

                // Filter provider
                if (filterProvider != null) {
                    mustQueries.add(term(WebNovelMetadataEsModel::providerId, filterProvider))
                }

                // Filter type
                when (filterType) {
                    WebNovelFilter.Type.连载中 -> WebNovelType.连载中
                    WebNovelFilter.Type.已完结 -> WebNovelType.已完结
                    WebNovelFilter.Type.短篇 -> WebNovelType.短篇
                    else -> null
                }?.let {
                    mustQueries.add(term(WebNovelMetadataEsModel::type, it.serialName()))
                }

                // Filter level
                when (filterLevel) {
                    WebNovelFilter.Level.一般向 -> mustNotQueries
                    WebNovelFilter.Level.R18 -> mustQueries
                    else -> null
                }?.add(
                    terms(
                        WebNovelMetadataEsModel::attentions,
                        WebNovelAttention.R18.serialName(),
                        WebNovelAttention.性描写.serialName(),
                    )
                )

                // Filter translate
                if (filterTranslate == WebNovelFilter.Translate.AI) {
                    mustQueries.add(ESQuery("term", JsonDsl().apply { put("hasGpt", true) }))
                }

                // Parse query
                val allAttentions = WebNovelAttention.entries.map { it.serialName() }
                val queryWords = mutableListOf<String>()
                userQuery
                    ?.split(" ")
                    ?.forEach { token ->
                        if (token.startsWith('>') || token.startsWith('<')) {
                            token.substring(1).toUIntOrNull()?.toInt()?.let { number ->
                                mustQueries.add(range(WebNovelMetadataEsModel::tocSize) {
                                    if (token.startsWith('>')) {
                                        gt = number
                                    } else {
                                        lt = number
                                    }
                                })
                                return@forEach
                            }
                        }

                        if (token.endsWith('$')) {
                            val rawToken = token.removePrefix("-").removeSuffix("$")
                            val queries =
                                if (token.startsWith("-")) mustNotQueries
                                else mustQueries
                            val field =
                                if (allAttentions.contains(rawToken)) WebNovelMetadataEsModel::attentions
                                else WebNovelMetadataEsModel::keywords
                            queries.add(term(field, rawToken))
                        } else {
                            queryWords.add(token)
                        }
                    }

                filter(mustQueries)
                mustNot(mustNotQueries)

                if (queryWords.isNotEmpty()) {
                    must(
                        simpleQueryString(
                            queryWords.joinToString(" "),
                            WebNovelMetadataEsModel::titleJp,
                            WebNovelMetadataEsModel::titleZh,
                            WebNovelMetadataEsModel::authors,
                            WebNovelMetadataEsModel::attentions,
                            WebNovelMetadataEsModel::keywords,
                        ) {
                            defaultOperator = MatchOperator.AND
                        }
                    )
                } else {
                    sort {
                        add(WebNovelMetadataEsModel::updateAt)
                    }
                }
            }
        }
        val items = response.hits?.hits
            ?.map { hit ->
                val esNovel = hit.parseHit<WebNovelMetadataEsModel>()
                mongo.webNovelMetadataCollection
                    .findOne(WebNovelMetadata.byId(esNovel.providerId, esNovel.novelId))!!
                    .toOutline()
            }
            ?: emptyList()
        val total = response.total
        return Page(items = items, total = total)
    }

    suspend fun get(
        providerId: String,
        novelId: String,
    ): WebNovelMetadata? {
        return mongo
            .webNovelMetadataCollection
            .findOne(WebNovelMetadata.byId(providerId, novelId))
    }

    private suspend fun getRemote(
        providerId: String,
        novelId: String,
    ): Result<WebNovelMetadata> {
        return provider
            .getMetadata(providerId, novelId)
            .map { remote ->
                WebNovelMetadata(
                    id = ObjectId(),
                    providerId = providerId,
                    novelId = novelId,
                    titleJp = remote.title,
                    authors = remote.authors.map { WebNovelAuthor(it.name, it.link) },
                    type = remote.type,
                    keywords = remote.keywords,
                    attentions = remote.attentions,
                    introductionJp = remote.introduction,
                    toc = remote.toc.map { WebNovelTocItem(it.title, null, it.chapterId, it.createAt) },
                )
            }
    }

    suspend fun getNovelAndSave(
        providerId: String,
        novelId: String,
        expiredMinutes: Int = 20 * 60,
    ): Result<WebNovelMetadata> {
        val local = get(providerId, novelId)

        // 不在数据库中
        if (local == null) {
            return getRemote(providerId, novelId)
                .onSuccess {
                    mongo
                        .webNovelMetadataCollection
                        .insertOne(it)
                    syncEs(it)
                }
        }

        // 在数据库中，暂停更新
        if (local.pauseUpdate) {
            return Result.success(local)
        }

        // 在数据库中，没有过期
        val minutes = ChronoUnit.MINUTES.between(local.syncAt, LocalDateTime.now())
        val isExpired = minutes > expiredMinutes
        if (!isExpired) {
            return Result.success(local)
        }

        // 在数据库中，过期，合并
        val remoteNovel = getRemote(providerId, novelId)
            .getOrElse {
                // 无法更新，大概率小说被删了
                return Result.success(local)
            }
        val merged = mergeNovel(
            providerId = providerId,
            novelId = novelId,
            local = local,
            remote = remoteNovel,
        )
        return Result.success(merged)
    }

    private suspend fun mergeNovel(
        providerId: String,
        novelId: String,
        local: WebNovelMetadata,
        remote: WebNovelMetadata,
    ): WebNovelMetadata {
        val merged = mergeToc(
            remoteToc = remote.toc,
            localToc = local.toc,
            isIdUnstable = isProviderIdUnstable(providerId)
        )
        if (merged.reviewReason != null) {
            mongo
                .webNovelTocMergeHistoryCollection
                .insertOne(
                    WebNovelTocMergeHistory(
                        id = ObjectId(),
                        providerId = providerId,
                        novelId = novelId,
                        tocOld = local.toc,
                        tocNew = remote.toc,
                        reason = merged.reviewReason,
                    )
                )
        }

        val list = mutableListOf(
            setValue(WebNovelMetadata::titleJp, remote.titleJp),
            setValue(WebNovelMetadata::type, remote.type),
            setValue(WebNovelMetadata::attentions, remote.attentions),
            setValue(WebNovelMetadata::keywords, remote.keywords),
            setValue(WebNovelMetadata::introductionJp, remote.introductionJp),
            setValue(WebNovelMetadata::toc, merged.toc),
            setValue(WebNovelMetadata::syncAt, LocalDateTime.now()),
        )
        if (merged.hasChanged) {
            list.add(setValue(WebNovelMetadata::changeAt, LocalDateTime.now()))
            list.add(setValue(WebNovelMetadata::updateAt, Clock.System.now()))
        }

        val novel = mongo
            .webNovelMetadataCollection
            .findOneAndUpdate(
                WebNovelMetadata.byId(providerId, novelId),
                combine(list),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )!!
        syncEs(novel)
        if (merged.hasChanged) {
            mongo.webNovelFavoriteCollection.updateMany(
                WebNovelFavoriteModel::novelId eq novel.id.toId(),
                setValue(WebNovelFavoriteModel::updateAt, novel.updateAt)
            )
        }
        return novel
    }

    suspend fun increaseVisited(providerId: String, novelId: String) {
        mongo
            .webNovelMetadataCollection
            .updateOne(
                WebNovelMetadata.byId(providerId, novelId),
                inc(WebNovelMetadata::visited, 1)
            )
    }

    suspend fun updateChapterTranslateState(
        providerId: String,
        novelId: String,
        translatorId: TranslatorId,
    ): Long {
        val zhProperty1 = when (translatorId) {
            TranslatorId.Baidu -> WebNovelChapter::baiduParagraphs
            TranslatorId.Youdao -> WebNovelChapter::youdaoParagraphs
            TranslatorId.Gpt -> WebNovelChapter::gptParagraphs
        }
        val zh = mongo.webNovelChapterCollection
            .countDocuments(
                and(
                    WebNovelChapter::providerId eq providerId,
                    WebNovelChapter::novelId eq novelId,
                    zhProperty1 ne null,
                )
            )
        val zhProperty = when (translatorId) {
            TranslatorId.Baidu -> WebNovelMetadata::baidu
            TranslatorId.Youdao -> WebNovelMetadata::youdao
            TranslatorId.Gpt -> WebNovelMetadata::gpt
        }
        mongo
            .webNovelMetadataCollection
            .updateOne(
                WebNovelMetadata.byId(providerId, novelId),
                combine(
                    setValue(zhProperty, zh),
                    setValue(WebNovelMetadata::changeAt, LocalDateTime.now()),
                ),
            )
        return zh
    }

    suspend fun updateTranslation(
        providerId: String,
        novelId: String,
        titleZh: Optional<String?>,
        introductionZh: Optional<String?>,
        tocZh: Map<Int, String?>,
    ): WebNovelMetadata? {
        val list = mutableListOf<Bson>()
        titleZh.ifSome {
            list.add(setValue(WebNovelMetadata::titleZh, it))
        }
        introductionZh.ifSome {
            list.add(setValue(WebNovelMetadata::introductionZh, it))
        }
        tocZh.forEach { (index, itemTitleZh) ->
            list.add(setValue(WebNovelMetadata::toc.pos(index) / WebNovelTocItem::titleZh, itemTitleZh))
        }
        list.add(setValue(WebNovelMetadata::changeAt, LocalDateTime.now()))

        return mongo
            .webNovelMetadataCollection
            .findOneAndUpdate(
                WebNovelMetadata.byId(providerId, novelId),
                combine(list),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )
            ?.also { syncEs(it) }
    }

    suspend fun updateGlossary(
        providerId: String,
        novelId: String,
        glossary: Map<String, String>,
    ) {
        mongo
            .webNovelMetadataCollection
            .updateOne(
                WebNovelMetadata.byId(providerId, novelId),
                combine(
                    setValue(WebNovelMetadata::glossaryUuid, UUID.randomUUID().toString()),
                    setValue(WebNovelMetadata::glossary, glossary),
                ),
            )
    }

    suspend fun updateWenkuId(
        providerId: String,
        novelId: String,
        wenkuId: String?,
    ): UpdateResult {
        return mongo
            .webNovelMetadataCollection
            .updateOne(
                WebNovelMetadata.byId(providerId, novelId),
                setValue(WebNovelMetadata::wenkuId, wenkuId),
            )
    }

    private suspend fun syncEs(
        novel: WebNovelMetadata,
    ) {
        es.client.indexDocument(
            id = "${novel.providerId}.${novel.novelId}",
            target = ElasticSearchDataSource.webNovelIndexName,
            document = WebNovelMetadataEsModel(
                providerId = novel.providerId,
                novelId = novel.novelId,
                titleJp = novel.titleJp,
                titleZh = novel.titleZh,
                authors = novel.authors.map { it.name },
                type = novel.type,
                keywords = novel.keywords,
                attentions = novel.attentions,
                tocSize = novel.toc.count { it.chapterId != null },
                hasGpt = novel.gpt > 0,
                updateAt = novel.updateAt.epochSeconds,
            ),
            refresh = Refresh.WaitFor,
        )
    }
}

private fun RemoteNovelListItem.toOutline(
    providerId: String,
    novel: WebNovelMetadata?,
) =
    WebNovelMetadataOutline(
        providerId = providerId,
        novelId = novelId,
        titleJp = title,
        titleZh = novel?.titleZh,
        type = null,
        attentions = attentions,
        keywords = keywords,
        total = novel?.toc?.count { it.chapterId != null }?.toLong() ?: 0,
        jp = novel?.jp ?: 0,
        baidu = novel?.baidu ?: 0,
        youdao = novel?.youdao ?: 0,
        gpt = novel?.gpt ?: 0,
        extra = extra,
        updateAt = novel?.updateAt,
    )

fun WebNovelMetadata.toOutline() =
    WebNovelMetadataOutline(
        providerId = providerId,
        novelId = novelId,
        titleJp = titleJp,
        titleZh = titleZh,
        type = type,
        attentions = attentions,
        keywords = keywords,
        total = toc.count { it.chapterId != null }.toLong(),
        jp = jp,
        baidu = baidu,
        youdao = youdao,
        gpt = gpt,
        extra = null,
        updateAt = updateAt,
    )

fun isProviderIdUnstable(providerId: String): Boolean {
    return providerId == Syosetu.id || providerId == Hameln.id
}

data class MergedResult(
    val toc: List<WebNovelTocItem>,
    val hasChanged: Boolean,
    val reviewReason: String?,
)

fun mergeToc(
    remoteToc: List<WebNovelTocItem>,
    localToc: List<WebNovelTocItem>,
    isIdUnstable: Boolean,
): MergedResult {
    return if (isIdUnstable) {
        mergeTocUnstable(remoteToc, localToc)
    } else {
        mergeTocStable(remoteToc, localToc)
    }
}

private fun mergeTocUnstable(
    remoteToc: List<WebNovelTocItem>,
    localToc: List<WebNovelTocItem>,
): MergedResult {
    val remoteIdToTitle = remoteToc.mapNotNull {
        if (it.chapterId == null) null
        else it.chapterId to it.titleJp
    }.toMap()
    val localIdToTitle = localToc.mapNotNull {
        if (it.chapterId == null) null
        else it.chapterId to it.titleJp
    }.toMap()

    if (remoteIdToTitle.size < localIdToTitle.size) {
        return MergedResult(
            simpleMergeToc(remoteToc, localToc),
            true,
            "有未知章节被删了"
        )
    } else {
        val hasEpisodeTitleChanged = localIdToTitle.any { (eid, localTitle) ->
            val remoteTitle = remoteIdToTitle[eid]
            remoteTitle != localTitle
        }
        return MergedResult(
            simpleMergeToc(remoteToc, localToc),
            remoteIdToTitle.size != localIdToTitle.size,
            if (hasEpisodeTitleChanged) "有章节标题变化" else null
        )
    }
}

private fun mergeTocStable(
    remoteToc: List<WebNovelTocItem>,
    localToc: List<WebNovelTocItem>,
): MergedResult {
    val remoteEpIds = remoteToc.mapNotNull { it.chapterId }
    val localEpIds = localToc.mapNotNull { it.chapterId }
    val noEpDeleted = remoteEpIds.containsAll(localEpIds)
    val noEpAdded = localEpIds.containsAll(remoteEpIds)
    return MergedResult(
        simpleMergeToc(remoteToc, localToc),
        !(noEpAdded && noEpDeleted),
        if (noEpDeleted) null else "有章节被删了"
    )
}

private fun simpleMergeToc(
    remoteToc: List<WebNovelTocItem>,
    localToc: List<WebNovelTocItem>,
): List<WebNovelTocItem> {
    return remoteToc.map { itemNew ->
        val itemOld = localToc.find { it.titleJp == itemNew.titleJp }
        if (itemOld?.titleZh == null) {
            itemNew
        } else {
            itemNew.copy(titleZh = itemOld.titleZh)
        }
    }
}
