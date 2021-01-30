package org.koitharu.kotatsu.core.parser.site

import androidx.collection.arraySetOf
import org.koitharu.kotatsu.base.domain.MangaLoaderContext
import org.koitharu.kotatsu.core.exceptions.ParseException
import org.koitharu.kotatsu.core.model.*
import org.koitharu.kotatsu.core.parser.RemoteMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.utils.ext.*
import java.util.*
import kotlin.collections.ArrayList

class DesuMeRepository(loaderContext: MangaLoaderContext) : RemoteMangaRepository(loaderContext) {

	override val source = MangaSource.DESUME

	override val defaultDomain = "desu.me"

	override val sortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL
	)

	override suspend fun getList(
		offset: Int,
		query: String?,
		sortOrder: SortOrder?,
		tag: MangaTag?
	): List<Manga> {
		val domain = getDomain()
		val url = buildString {
			append("https://")
			append(domain)
			append("/manga/api/?limit=20&order=")
			append(getSortKey(sortOrder))
			append("&page=")
			append((offset / 20) + 1)
			if (tag != null) {
				append("&genres=")
				append(tag.key)
			}
			if (query != null) {
				append("&search=")
				append(query)
			}
		}
		val json = loaderContext.httpGet(url).parseJson().getJSONArray("response")
			?: throw ParseException("Invalid response")
		val total = json.length()
		val list = ArrayList<Manga>(total)
		for (i in 0 until total) {
			val jo = json.getJSONObject(i)
			val cover = jo.getJSONObject("image")
			val id = jo.getLong("id")
			list += Manga(
				url = "/manga/api/$id",
				source = MangaSource.DESUME,
				title = jo.getString("russian"),
				altTitle = jo.getString("name"),
				coverUrl = cover.getString("preview"),
				largeCoverUrl = cover.getString("original"),
				state = when {
					jo.getInt("ongoing") == 1 -> MangaState.ONGOING
					else -> null
				},
				rating = jo.getDouble("score").toFloat().coerceIn(0f, 1f),
				id = generateUid(id),
				description = jo.getString("description")
			)
		}
		return list
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.withDomain()
		val json = loaderContext.httpGet(url).parseJson().getJSONObject("response")
			?: throw ParseException("Invalid response")
		val baseChapterUrl = manga.url + "/chapter/"
		return manga.copy(
			tags = json.getJSONArray("genres").mapToSet {
				MangaTag(
					key = it.getString("text"),
					title = it.getString("russian"),
					source = manga.source
				)
			},
			description = json.getString("description"),
			chapters = json.getJSONObject("chapters").getJSONArray("list").mapIndexed { i, it ->
				val chid = it.getLong("id")
				MangaChapter(
					id = generateUid(chid),
					source = manga.source,
					url = "$baseChapterUrl$chid",
					name = it.optString("title", "${manga.title} #${it.getDouble("ch")}"),
					number = i + 1
				)
			}
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.withDomain()
		val json = loaderContext.httpGet(fullUrl)
			.parseJson()
			.getJSONObject("response") ?: throw ParseException("Invalid response")
		return json.getJSONObject("pages").getJSONArray("list").map { jo ->
			MangaPage(
				id = generateUid(jo.getLong("id")),
				referer = fullUrl,
				source = chapter.source,
				url = jo.getString("img")
			)
		}
	}

	override suspend fun getTags(): Set<MangaTag> {
		val doc = loaderContext.httpGet("https://${getDomain()}/manga/").parseHtml()
		val root = doc.body().getElementById("animeFilter").selectFirst(".catalog-genres")
		return root.select("li").mapToSet {
			MangaTag(
				source = source,
				key = it.selectFirst("input").attr("data-genre"),
				title = it.selectFirst("label").text()
			)
		}
	}

	override fun onCreatePreferences() = arraySetOf(SourceSettings.KEY_DOMAIN)

	private fun getSortKey(sortOrder: SortOrder?) =
		when (sortOrder) {
			SortOrder.ALPHABETICAL -> "name"
			SortOrder.POPULARITY -> "popular"
			SortOrder.UPDATED -> "updated"
			SortOrder.NEWEST -> "id"
			else -> "updated"
		}
}