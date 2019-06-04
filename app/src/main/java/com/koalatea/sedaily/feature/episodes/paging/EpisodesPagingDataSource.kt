package com.koalatea.sedaily.feature.episodes.paging

import androidx.lifecycle.MutableLiveData
import androidx.paging.ItemKeyedDataSource
import com.koalatea.sedaily.database.EpisodeDao
import com.koalatea.sedaily.model.Episode
import com.koalatea.sedaily.model.SearchQuery
import com.koalatea.sedaily.network.NetworkState
import com.koalatea.sedaily.network.SEDailyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EpisodesPagingDataSource(
        private val searchQuery: SearchQuery,
        private val pageSize: Int,
        private val api: SEDailyApi,
        private val episodeDao: EpisodeDao
) : ItemKeyedDataSource<String?, Episode>() {

    val networkState = MutableLiveData<NetworkState>()
    val refreshState = MutableLiveData<NetworkState>()

    override fun loadInitial(params: LoadInitialParams<String?>, callback: LoadInitialCallback<Episode>) = load(params.requestedInitialKey, callback, isInitial = true)

    override fun loadAfter(params: LoadParams<String?>, callback: LoadCallback<Episode>) = load(params.key, callback, isInitial = false)

    override fun loadBefore(params: LoadParams<String?>, callback: LoadCallback<Episode>) {
        // Ignored, since we only ever append to our initial load.
    }

    override fun getKey(item: Episode) = item.date ?: ""

    private fun load(key: String?, callback: LoadCallback<Episode>, isInitial: Boolean) {
        GlobalScope.launch(Dispatchers.Main) {
            val createdAtBefore = if (isInitial) { null } else { key }

            networkState.postValue(NetworkState.Loading)
            if (isInitial) {
                refreshState.postValue(NetworkState.Loading)
            }

            val response = withContext(Dispatchers.IO) {
                api.getPostsAsync(searchQuery.searchTerm, searchQuery.categoryId, createdAtBefore, pageSize).await()
            }

            // Only cache the first page when searching for all podcasts.
            val isCachable = createdAtBefore.isNullOrBlank() && searchQuery.categoryId.isNullOrBlank()
            if (response.isSuccessful) {
                val episodes = response.body() ?: listOf()

                // Clear old cached data.
                episodeDao.clearTable()
                if (isCachable) {
                    episodeDao.insert(episodes)
                }

                callback.onResult(episodes)
                networkState.postValue(NetworkState.Loaded(episodes.size))
                if (isInitial) {
                    refreshState.postValue(NetworkState.Loaded(episodes.size))
                }
            } else {
                val episodes = episodeDao.getEpisodes()
                if (isCachable && !episodes.isNullOrEmpty()) {
                    callback.onResult(episodes)
                    networkState.postValue(NetworkState.Loaded(episodes.size))
                    if (isInitial) {
                        refreshState.postValue(NetworkState.Loaded(episodes.size))
                    }
                } else {
                    val error = NetworkState.Error(response.errorBody()?.string() ?: "Unknown error")

                    networkState.postValue(error)
                    if (isInitial) {
                        refreshState.postValue(error)
                    }
                }
            }
        }
    }

}