package org.koitharu.kotatsu.suggestions.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.list.domain.ListFilterOption

@Dao
abstract class SuggestionDao {

	@Transaction
	@Query("SELECT * FROM suggestions ORDER BY relevance DESC")
	abstract fun observeAll(): Flow<List<SuggestionWithManga>>

	fun observeAll(limit: Int, filterOptions: Collection<ListFilterOption>): Flow<List<SuggestionWithManga>> {
		val query = buildString {
			append("SELECT * FROM suggestions")
			if (filterOptions.isNotEmpty()) {
				append(" WHERE")
				var isFirst = true
				val groupedOptions = filterOptions.groupBy { it.groupKey }
				for ((_, group) in groupedOptions) {
					if (group.isEmpty()) {
						continue
					}
					if (isFirst) {
						isFirst = false
						append(' ')
					} else {
						append(" AND ")
					}
					if (group.size > 1) {
						group.joinTo(this, separator = " OR ", prefix = "(", postfix = ")") {
							it.getCondition()
						}
					} else {
						append(group.single().getCondition())
					}
				}
			}
			append(" ORDER BY relevance DESC")
			if (limit > 0) {
				append(" LIMIT ")
				append(limit)
			}
		}
		return observeAllImpl(SimpleSQLiteQuery(query))
	}

	@Transaction
	@Query("SELECT * FROM suggestions ORDER BY RANDOM() LIMIT 1")
	abstract suspend fun getRandom(): SuggestionWithManga?

	@Transaction
	@Query("SELECT * FROM suggestions ORDER BY RANDOM() LIMIT :limit")
	abstract suspend fun getRandom(limit: Int): List<SuggestionWithManga>

	@Query("SELECT COUNT(*) FROM suggestions")
	abstract suspend fun count(): Int

	@Query("SELECT manga.title FROM suggestions LEFT JOIN manga ON suggestions.manga_id = manga.manga_id WHERE manga.title LIKE :query")
	abstract suspend fun getTitles(query: String): List<String>

	@Query("SELECT tags.* FROM suggestions LEFT JOIN tags ON (tag_id IN (SELECT tag_id FROM manga_tags WHERE manga_tags.manga_id = suggestions.manga_id)) GROUP BY tag_id ORDER BY COUNT(tags.tag_id) DESC LIMIT :limit")
	abstract suspend fun getTopTags(limit: Int): List<TagEntity>

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insert(entity: SuggestionEntity): Long

	@Update
	abstract suspend fun update(entity: SuggestionEntity): Int

	@Query("DELETE FROM suggestions")
	abstract suspend fun deleteAll()

	@Transaction
	open suspend fun upsert(entity: SuggestionEntity) {
		if (update(entity) == 0) {
			insert(entity)
		}
	}

	@Transaction
	@RawQuery(observedEntities = [SuggestionEntity::class])
	protected abstract fun observeAllImpl(query: SupportSQLiteQuery): Flow<List<SuggestionWithManga>>

	private fun ListFilterOption.getCondition(): String = when (this) {
		ListFilterOption.Macro.NSFW -> "(SELECT nsfw FROM manga WHERE manga.manga_id = suggestions.manga_id) = 1"
		is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags WHERE manga_tags.manga_id = suggestions.manga_id AND tag_id = ${tag.toEntity().id})"
		else -> throw IllegalArgumentException("Unsupported option $this")
	}
}
