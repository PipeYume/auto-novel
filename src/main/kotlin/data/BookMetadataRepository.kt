package data

import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import data.elasticsearch.EsBookMetadataRepository
import data.provider.ProviderDataSource
import data.provider.SBookMetadata
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.bson.conversions.Bson
import org.litote.kmongo.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class BookAuthor(
    val name: String,
    val link: String?,
)

@Serializable
data class BookTocItem(
    val titleJp: String,
    val titleZh: String?,
    val episodeId: String?,
)

@Serializable
data class BookMetadata(
    val providerId: String,
    val bookId: String,
    val titleJp: String,
    val titleZh: String?,
    val authors: List<BookAuthor>,
    val introductionJp: String,
    val introductionZh: String?,
    val glossaryUuid: String? = null,
    val glossary: Map<String, String> = emptyMap(),
    val toc: List<BookTocItem>,
    val visited: Long,
    val downloaded: Long,
    @Contextual val syncAt: LocalDateTime,
    @Contextual val changeAt: LocalDateTime,
)

data class BookRankItem(
    val bookId: String,
    val titleJp: String,
    val titleZh: String?,
    val extra: String,
)

private fun SBookMetadata.toDb(providerId: String, bookId: String) =
    BookMetadata(
        providerId = providerId,
        bookId = bookId,
        titleJp = title,
        titleZh = null,
        authors = authors.map { BookAuthor(it.name, it.link) },
        introductionJp = introduction,
        introductionZh = null,
        toc = toc.map { BookTocItem(it.title, null, it.episodeId) },
        visited = 0,
        downloaded = 0,
        syncAt = LocalDateTime.now(),
        changeAt = LocalDateTime.now(),
    )

class BookMetadataRepository(
    private val providerDataSource: ProviderDataSource,
    private val mongoDataSource: MongoDataSource,
    private val esBookMetadataRepository: EsBookMetadataRepository,
) {
    private val col
        get() = mongoDataSource.database.getCollection<BookMetadata>("metadata")

    init {
        runBlocking {
            col.ensureUniqueIndex(
                BookMetadata::providerId,
                BookMetadata::bookId,
            )
        }
    }

    suspend fun listRank(
        providerId: String,
        options: Map<String, String>,
    ): Result<List<BookRankItem>> {
        @Serializable
        data class BookIdWithTitleZh(val bookId: String, val titleZh: String?)

        return providerDataSource.getRank(providerId, options).map { items ->
            val idToTitleZh = col
                .withDocumentClass<BookIdWithTitleZh>()
                .find(
                    BookMetadata::providerId eq providerId,
                    BookMetadata::bookId `in` items.map { it.bookId },
                )
                .toList()
                .associate { it.bookId to it.titleZh }
            items.map {
                BookRankItem(
                    bookId = it.bookId,
                    titleJp = it.title,
                    titleZh = idToTitleZh[it.bookId],
                    extra = it.meta,
                )
            }
        }
    }

    // Element operations
    private fun bsonSpecifyMetadata(providerId: String, bookId: String): Bson {
        return and(
            BookMetadata::providerId eq providerId,
            BookMetadata::bookId eq bookId,
        )
    }

    suspend fun increaseVisited(providerId: String, bookId: String) {
        col.updateOne(
            bsonSpecifyMetadata(providerId, bookId),
            inc(BookMetadata::visited, 1)
        )
    }

    suspend fun increaseDownloaded(providerId: String, bookId: String) {
        col.updateOne(
            bsonSpecifyMetadata(providerId, bookId),
            inc(BookMetadata::downloaded, 1)
        )
    }

    suspend fun getLocal(providerId: String, bookId: String): BookMetadata? {
        return col.findOne(
            bsonSpecifyMetadata(providerId, bookId),
        )
    }

    private suspend fun getRemote(providerId: String, bookId: String): Result<BookMetadata> {
        return providerDataSource
            .getMetadata(providerId, bookId)
            .map { it.toDb(providerId, bookId) }
    }

    suspend fun get(providerId: String, bookId: String): Result<BookMetadata> {
        // 不在数据库中
        val metadataLocal = getLocal(providerId, bookId)
            ?: return getRemote(providerId, bookId)
                .onSuccess {
                    col.insertOne(it)
                    syncEs(it)
                }

        // 在数据库中，没有过期
        val days = ChronoUnit.DAYS.between(metadataLocal.syncAt, LocalDateTime.now())
        val isExpired = days > 2
        if (!isExpired) {
            return Result.success(metadataLocal)
        }

        // 在数据库中，过期，合并
        return getRemote(providerId, bookId).map { metadataRemote ->
            mergeRemoteMetadataToLocal(
                providerId = providerId,
                bookId = bookId,
                metadataLocal = metadataLocal,
                metadataRemote = metadataRemote,
            )!!
        }.recover {
            it.printStackTrace()
            metadataLocal
        }
    }

    private suspend fun mergeRemoteMetadataToLocal(
        providerId: String,
        bookId: String,
        metadataLocal: BookMetadata,
        metadataRemote: BookMetadata,
    ): BookMetadata? {
        val list = mutableListOf<Bson>()

        metadataRemote.titleJp.let {
            list.add(setValue(BookMetadata::titleJp, it))
        }

        metadataRemote.introductionJp.let {
            list.add(setValue(BookMetadata::introductionJp, it))
        }

        metadataRemote.toc.map { itemNew ->
            val itemOld = metadataLocal.toc.find { it.titleJp == itemNew.titleJp }
            if (itemOld?.titleZh == null) {
                itemNew
            } else {
                itemNew.copy(titleZh = itemOld.titleZh)
            }
        }.let {
            list.add(setValue(BookMetadata::toc, it))
        }

        if (list.isNotEmpty()) {
            list.add(setValue(BookMetadata::changeAt, LocalDateTime.now()))
        }
        list.add(setValue(BookMetadata::syncAt, LocalDateTime.now()))

        return col.findOneAndUpdate(
            bsonSpecifyMetadata(providerId, bookId),
            combine(list),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.also { syncEs(it) }
    }

    suspend fun updateZh(
        providerId: String,
        bookId: String,
        titleZh: String?,
        introductionZh: String?,
        glossary: Map<String, String>?,
        tocZh: Map<Int, String>,
    ) {
        val list = mutableListOf<Bson>()
        titleZh?.let {
            list.add(setValue(BookMetadata::titleZh, it))
        }
        introductionZh?.let {
            list.add(setValue(BookMetadata::introductionZh, it))
        }
        glossary?.let {
            list.add(setValue(BookMetadata::glossaryUuid, UUID.randomUUID().toString()))
            list.add(setValue(BookMetadata::glossary, it))
        }
        tocZh.forEach { (index, itemTitleZh) ->
            list.add(setValue(BookMetadata::toc.pos(index) / BookTocItem::titleZh, itemTitleZh))
        }
        list.add(setValue(BookMetadata::changeAt, LocalDateTime.now()))

        col.findOneAndUpdate(
            bsonSpecifyMetadata(providerId, bookId),
            combine(list),
        )?.let { syncEs(it) }
    }

    suspend fun updateChangeAt(providerId: String, bookId: String) {
        col.findOneAndUpdate(
            bsonSpecifyMetadata(providerId, bookId),
            setValue(BookMetadata::changeAt, LocalDateTime.now()),
        )?.let { syncEs(it) }
    }

    private suspend fun syncEs(metadata: BookMetadata) {
        esBookMetadataRepository.index(
            metadata.providerId,
            metadata.bookId,
            metadata.titleJp,
            metadata.titleZh,
            metadata.authors.map { it.name },
            metadata.changeAt.atZone(ZoneId.systemDefault()).toEpochSecond(),
        )
    }
}