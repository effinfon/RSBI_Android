package ro.rsbideveloper.rsbi

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageManager.ACTION_CLEAR_APP_CACHE
import android.os.storage.StorageManager.ACTION_MANAGE_STORAGE
import android.util.Log
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import ro.rsbideveloper.rsbi.data.article.Article
import ro.rsbideveloper.rsbi.data.article.ArticleDao
import ro.rsbideveloper.rsbi.data.article.ArticleDatabase
import java.io.File
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.util.*

// <TODO> change blue action button in keyboard (~ Imm ?)
// <TODO> the act of getting data from the remote server needs to be out of the activity or it gets triggered too often on configuration change
// <TODO> also, getting the data is not yet asynchronous; and it also needs to have a smaller temporal granularity for synchronizing the LiveData<> with the suspend function that gets the data; also, first check the caches, then try to get check for synchronization and eventually also get the new data (or eliminate pre-existing data !)

// <TODO> try to synchronize with the server every 15 minutes or so, after the app has been turned on
// (*?) what about marking an article with a ~ "New article" sign, when freshly synchronized for the first time ?
// <TODO> getCacheQuotaBytes()
    // Log.d("SYNCHRONIZE", "Logging parseHTML(): getCacheQuotaBytes() -> how to call it ?")


class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    /// Essentials
    private val database: ArticleDatabase = ArticleDatabase.getDatabase(application)
    private val databaseDao: ArticleDao = database.getDao()


    val liveArticles: LiveData<List<Article?>> = databaseDao.selectAllLiveData()
    var queryLiveData: LiveData<List<Article?>>? = null
    private val staticUrlsArticles: List<String> = application.resources.getStringArray(R.array.articles_urls).toList()
    // other data class and its static urls


    // <TODO> ?
    companion object {
        var totalCacheSize = 0
    }


    /// accessing
    private val resources = application.resources
    private val cacheDir = application.cacheDir
    private val externalCacheDir = application.externalCacheDir
    private val filesDir = application.filesDir

    // <TODO> what to do about this ?
    private val baseContext = application.baseContext
    private val applicationContext = application.applicationContext

    private var initialSynchronizationOccured = false   // <TODO> keep / no keep ?




    fun <T> concatenate(vararg lists: List<T>): List<T> {
        return listOf(*lists).flatten()
    }

    fun selectArticleByPrimaryKey(detailedArticleURL: String): LiveData<Article?> {
        return databaseDao.selectByIdLiveData(detailedArticleURL)
    }


    fun removeObserversFromQueryLiveData(owner: androidx.lifecycle.LifecycleOwner) {
        queryLiveData?.removeObservers(owner)
        queryLiveData = null
    }

    fun selectArticlesByQuery(query: String): LiveData<List<Article?>> {
        // this can work by way of multiple database queries, then eliminating duplicates, ordering based on priorities, etc.

        val filterByCategory = databaseDao.selectByCategoryLiveData(query).value
        val filterByTitle = databaseDao.selectByTitleLiveData(query).value
        val filterByAuthor = databaseDao.selectByAuthorLiveData(query).value
        val filterByCreationTime = databaseDao.selectByDatetimeLiveData(query).value

        Log.d("MERGER", "Category: $filterByCategory")
        Log.d("MERGER", "Title: $filterByTitle")
        Log.d("MERGER", "Author: $filterByAuthor")
        Log.d("MERGER", "Datetime: $filterByCreationTime")



        val accumulator = listOf<Article?>()

        filterByCategory?.let {
            concatenate(accumulator, it)
        }
        filterByTitle?.let {
            concatenate(accumulator, it)
        }
        filterByAuthor?.let {
            concatenate(accumulator, it)
        }
        filterByCreationTime?.let {
            concatenate(accumulator, it)
        }

        Log.d("MERGER", "All: $accumulator")

        //return accumulator

        queryLiveData = databaseDao.selectByCategoryOrTitleOrAuthorOrDatetimeLiveData(query)
        return queryLiveData!!
    }






    fun listAllDetailedURLs(): List<String> {
        val urls: MutableList<String> = mutableListOf()
        Log.d("SYNCHRONIZE", "Logging listAllDetailedURLs(): start enumerating list")
        for(article in databaseDao.selectAll()) {
            article?.let {
                urls.add(it.detailedArticleURL)
                Log.d("SYNCHRONIZE", "Logging listAllDetailedURLs(): added article ${it.detailedArticleURL}")
            }
        }

        return urls.toList()
    }

    private fun hashFilenameFromURL(url: String): String {
        return BigInteger(1, MessageDigest.getInstance("MD5").digest(url.toByteArray()))
            .toString(16).padStart(32, '0')
    }

    private fun listAllCachedFiles(): List<URI> {
        try {
            val Uris: MutableList<URI> = mutableListOf()

            // <TODO> apparently this is unsafe, so use a ?.let call; or just check for nullity, else return an empty list
            Log.d("SYNCHRONIZE", "Logging listAllCachedFiles(): there are ${filesDir.listFiles().size} files")

            for(file in filesDir.listFiles()) {
                Log.d("SYNCHRONIZE", "Logging listAllCachedFiles():" +
                        "\n-> {filename: ${file.name}\nabsolute path: ${file.absolutePath}\ncanonical path: ${file.canonicalPath}" +
                        "\nURI: ${file.toURI()}\nURI.toASCII: ${file.toURI().toASCIIString()}\nURI.toURL: ${file.toURI().toURL()}}")

                Uris.add(file.toURI())
            }

            return Uris.toList()
        } catch(e: Exception) {
            Log.d("SYNCHRONIZE", "Logging listAllCachedFiles(): ${e.message}")
            return listOf()
        }
    }

    // <TODO> this is problematic because of the convention for URL that WebView expects and I have no
        //  way of knowing what it is, and I won't search for it. I am tired of this shit
    fun getURLOfCachedFileByArticleUrl(url: String): String {
        val filename = hashFilenameFromURL(url)

        return try {
            val file = File(filesDir, "$filename.html")
            file.toURI().toURL().toString()
        } catch(e: Exception) {
            Log.d("SYNCHRONIZE", "Logging selectCachedURL(): could not find file $filename")
            ""
        }
    }

    fun getHTMLOfCachedFileByArticleUrl(url: String): String {
        val filename = hashFilenameFromURL(url)

        return try {
            val codeHTMLBuilder = java.lang.StringBuilder()

            // <TODO> this depends on the modification of manually appending the .html file format
            val file = File(filesDir, "$filename.html")
            val lines = file.readLines()

            Log.d("SYNCHRONIZE", "Logging getHTMLOfCachedFileByArticleUrl(): reading line by line ->")
            lines.forEach { line ->
                codeHTMLBuilder.append(line)
                Log.d("SYNCHRONIZE", line)
            }

            codeHTMLBuilder.toString()
        } catch(e: Exception) {
            Log.d("SYNCHRONIZE", "Logging selectCachedURL(): could not find file $filename")
            ""
        }
    }

    private fun allocateCacheSpace() {
        try {
            // App needs 10 MB within internal storage.
            val cacheBytesRequired =
                1024 * 1024 * 10L;    // <TODO> 10 MB is probably more than needed; so far it's about 3.6 MB

            Log.d("SYNCHRONIZE", "Logging allocateCacheSpace(): requiring $cacheBytesRequired bytes")

            if (Build.VERSION.SDK_INT >= 26) {
                val storageManager = applicationContext.getSystemService<StorageManager>()!!
                val appSpecificInternalDirUuid: UUID = storageManager.getUuidForPath(filesDir)

                val availableBytes: Long =
                    storageManager.getAllocatableBytes(appSpecificInternalDirUuid)

                if (availableBytes >= cacheBytesRequired) {
                    Log.d(
                        "SYNCHRONIZE",
                        "Logging allocateCacheSpace(): $cacheBytesRequired bytes are available for allocation"
                    )

                    storageManager.allocateBytes(appSpecificInternalDirUuid, cacheBytesRequired)
                    Log.d(
                        "SYNCHRONIZE",
                        "Logging allocateCacheSpace(): $cacheBytesRequired bytes allocated"
                    )
                } else {
                    Log.d(
                        "SYNCHRONIZE",
                        "Logging allocateCacheSpace(): there are not enough bytes available for cache allocation"
                    )

                    val storageIntent = Intent().apply {
                        action = ACTION_MANAGE_STORAGE  // <TODO> what's the difference here ?
                        action = ACTION_CLEAR_APP_CACHE
                    }
                    baseContext.startActivity(storageIntent)    // I suppose this is what needed to be done to use the Intent ?
                }
            } else {
                // <TODO> what to do for versions < API level 26 to allocate some cache space ?
            }
        } catch(e: Exception) {
            Log.d("SYNCHRONIZE", "Exception allocateCacheSpace(): ${e.message}")
        }
    }







    fun initialSynchronization() {
        if(!initialSynchronizationOccured) {
            // <TODO> list all files; URI or URL ?
//            listAllCachedFiles()

            // <TODO> how to check for already allocated cache space ?
            allocateCacheSpace()

            synchronize()
            initialSynchronizationOccured = true
        }
    }

    fun synchronize() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                staticUrlsArticles.forEach { url ->
                    download(url)?.let { html ->
                        parseHTML(html, url)
                    }
                }
            } catch(e: Exception) {
                Log.d("SYNCHRONIZE", "Exception synchronizeArticles(): ${e.message}")
            }
        }
    }

    private suspend fun download(url: String): String? {
        try {
            Log.d("SYNCHRONIZE", "Logging download(): downloading from ${url}")

            val client = HttpClient(CIO)
            val response: HttpResponse = client.get(url)
            val content = StringBuilder()
            var line: String? = ""

            while (line != null) {
                content.append(line)
                line =
                    response.content.readUTF8Line(65536) // <TODO> what to do about this hard-coded value (which isn't even constrained by much, by design) ? (64KB)
            }
            client.close()

            Log.d("SYNCHRONIZE", "Logging download(): obtained HTML\n$content")

            return if (content.isNotEmpty()) {
                content.toString()
            } else {
                null
            }
        } catch(e: Exception) {
            Log.d("SYNCHRONIZE", "Exception download(): ${e.message}")
            return null
        }
    }


    private suspend fun parseHTML(codeHTML: String, sourceURL: String) {
        try {
            val document = Jsoup.parse(codeHTML)
            val articles = document.body().select("article")

            for (article in articles) {
                val title = article.select(".entry-title a").attr("title")
                val detailedArticleURL = article.select(".entry-title a").attr("href")
                val imgURL = article.select(".card-image img")
                    .attr("src")  // <TODO> might be missing, not all articles have a photo -> placeholders images based on ?? (category ? author ? title ?)
                val content = article.select(".entry-summary p").text()
                    .toString().replace("Read more???", "", true) // <TODO> remove "Read more"
                val category = article.select(".category").text()
                val categoryURL = article.select(".category").attr("href")  // <TODO> missing
                val author = article.select("div .author a")
                    .attr("title")  // <TODO> there is the alternative with <b>.text()
                val authorURL = article.select("div .author a").attr("href")
                val creationTime = article.select(".entry-date").attr("datetime").toString()
                    .replace("T", " ", true)
                    .replace("+", " GMT+", true)
                val latestModificationTime = article.select(".updated").attr("datetime").toString()
                    .replace("T", " ", true)
                    .replace("+", " GMT+", true)
                val language = articles.select("html").attr("lang")

                Log.d("SYNCHRONIZE", "Logging parseHTML(): extracted article\n"+
                        "-> {sourceUrl: $sourceURL,\ntitle: $title,\ndetailedArticleURL: $detailedArticleURL,\nimgURL: $imgURL," +
                        "\ncontent: $content,\ncategory: $category,\ncategoryURL: $categoryURL,\nauthor: $author," +
                        "\nauthorURL: $authorURL,\ncreationTime: $creationTime,\nlatestModificationTime: $latestModificationTime}" +
                        "\nlanguage: $language}")

                val extractedArticle = Article(
                    sourceURL, title, detailedArticleURL, imgURL,
                    content, category, categoryURL, author,
                    authorURL, creationTime, latestModificationTime, language)


                // <TODO> I tried using multiple attributes as primary key, but the whole Room.Dao started messing up
                    // I am thinking of adding a suffix to the detailedArticleURL (which I will have to keep sub-stringing everywhere)
                    // with the language; that way, the update would also differentiate between the two languages;
                    // do note that some articles do not even have content in the romanian language, so it's not THAT important
                val result = databaseDao.selectById(detailedArticleURL)
                if(result != null) {
                    if(result != extractedArticle) {
                        Log.d("SYNCHRONIZE", "Logging parseHTML(): updating article")
                        databaseDao.update(extractedArticle)
                    } else {
                        Log.d("SYNCHRONIZE", "Logging parseHTML(): the article does not require updating")
                    }
                } else {
                    Log.d("SYNCHRONIZE", "Logging parseHTML(): inserting article")
                    databaseDao.insert(extractedArticle)
                }



                val articleCodeHTML = download(extractedArticle.detailedArticleURL)
                if(articleCodeHTML != null) {

                    val filename = hashFilenameFromURL(extractedArticle.detailedArticleURL)
                    Log.d("SYNCHRONIZE", "Logging onParseHTML(): obtained filename hash $filename")

                    // <TODO> memory space, total file size, query available
                    val overhead = 1024
                    val requiredSpace = articleCodeHTML.toByteArray().size + overhead  // TODO how much is the "file overhead" actually
                    Log.d("SYNCHRONIZE", "Logging parseHTML(): required space $requiredSpace bytes")
                    totalCacheSize += requiredSpace
                    Log.d("SYNCHRONIZE", "Logging parseHTML(): total cache size $totalCacheSize bytes")

                    if (cacheDir.freeSpace > requiredSpace) {
                        val cacheFile = baseContext
                            .openFileOutput("$filename.html", Context.MODE_PRIVATE).use { file ->
                                file.write(articleCodeHTML.toByteArray())
                            }
                    } else {
                        // <TODO> not enough space - internal memory
                    }
                }
            }



            val nextHref = document.body().select("nav .nav-links .next").attr("href")
            if (nextHref.isNotEmpty()) {
                Log.d("SYNCHRONIZE", "next href: ${nextHref.toString()}")
                val html = download(nextHref)
                if (html != null) {
                    parseHTML(html, nextHref)
                }
            }
        } catch(e: Exception) {
            Log.d("SYNCHRONIZE", "Exception parseHTML(): ${e.message}")
        }
    }

    //////


    // HTML
    // Articles
    // Images

    // Repo, Database, Dao, ..?
    // Sync data with remote server, async propagate data through app (callbacks, LiveData)

}