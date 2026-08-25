package com.example.data

import com.example.data.model.PluginEntity
import com.example.data.model.RepoEntity

object DefaultPlugins {

    val DEFAULT_REPOS = listOf(
        RepoEntity(
            id = "nuvio-yoruix",
            name = "Yoruix Nuvio Providers",
            url = "https://raw.githubusercontent.com/yoruix/nuvio-providers/refs/heads/main/manifest.json",
            description = "Official Nuvio providers repository by Yoruix with 4K/1080p stream resolvers.",
            pluginCount = 0
        ),
        RepoEntity(
            id = "nuvio-all-in-one",
            name = "All-in-One Nuvio",
            url = "https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json",
            description = "All-in-One community provider collection for movies, TV series, and anime.",
            pluginCount = 0
        )
    )

    fun getDefaultPlugins(): List<PluginEntity> {
        return listOf(
            PluginEntity(
                id = "nuvio-yts",
                name = "YTS Movie Torrents (4K & 1080p)",
                description = "High-speed multi-quality P2P torrent stream resolver for Movies with verified seed health.",
                version = "2.2.0",
                author = "Nuvio Core",
                repoUrl = "https://yts.mx",
                isEnabled = true,
                supportedTypes = "movie",
                orderPriority = 1,
                jsCode = """
                    async function getStreams(params) {
                        const streams = [];
                        const { type, id, imdbId } = params;
                        const targetImdb = (imdbId || id || "").trim();
                        if (type !== "movie" || !targetImdb || !targetImdb.startsWith("tt")) {
                            return streams;
                        }

                        try {
                            const url = "https://yts.mx/api/v2/list_movies.json?query_term=" + encodeURIComponent(targetImdb);
                            const res = await fetch(url);
                            if (!res.ok) return streams;
                            const data = await res.json();

                            if (data && data.data && data.data.movies && data.data.movies.length > 0) {
                                const movie = data.data.movies[0];
                                const torrents = movie.torrents || [];

                                for (const t of torrents) {
                                    if (!t.hash) continue;
                                    const rawQuality = t.quality || "1080p";
                                    const qualityLabel = rawQuality === "2160p" ? "4K" : (rawQuality === "1080p" ? "1080p" : "720p");
                                    const typeLabel = t.type ? t.type.toUpperCase() : "WEB";
                                    const sizeStr = t.size || "Unknown Size";
                                    const seeds = t.seeds || 0;

                                    streams.push({
                                        name: "[Nuvio] YTS " + qualityLabel,
                                        title: movie.title + " (" + movie.year + ")\n" + qualityLabel + " • " + typeLabel + " • " + sizeStr + " • 👤 " + seeds + " seeds",
                                        infoHash: t.hash.toLowerCase(),
                                        quality: qualityLabel,
                                        provider: "YTS",
                                        size: sizeStr,
                                        format: typeLabel
                                    });
                                }
                            }
                        } catch (e) {
                            console.log("YTS error: " + e.message);
                        }

                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-eztv",
                name = "EZTV Series Torrents",
                description = "Automated TV show and episode torrent stream resolver with live seed health tracking.",
                version = "2.2.0",
                author = "Nuvio Core",
                repoUrl = "https://eztv.re",
                isEnabled = true,
                supportedTypes = "series",
                orderPriority = 2,
                jsCode = """
                    async function getStreams(params) {
                        const streams = [];
                        const { type, id, season, episode, imdbId } = params;
                        const targetImdb = (imdbId || id || "").trim();
                        if (type !== "series" || !targetImdb) {
                            return streams;
                        }

                        const cleanImdb = targetImdb.replace(/^tt/, '');
                        const targetSeason = season || 1;
                        const targetEpisode = episode || 1;

                        try {
                            const url = "https://eztv.re/api/get-torrents?imdb_id=" + encodeURIComponent(cleanImdb) + "&limit=100";
                            const res = await fetch(url);
                            if (!res.ok) return streams;
                            const data = await res.json();

                            if (data && data.torrents && Array.isArray(data.torrents)) {
                                for (const t of data.torrents) {
                                    const s = parseInt(t.season);
                                    const e = parseInt(t.episode);

                                    if (s === targetSeason && e === targetEpisode && t.hash) {
                                        let q = "720p";
                                        const titleLower = (t.title || "").toLowerCase();
                                        if (titleLower.includes("1080p")) q = "1080p";
                                        else if (titleLower.includes("2160p") || titleLower.includes("4k")) q = "4K";
                                        else if (titleLower.includes("480p")) q = "480p";

                                        const seeds = t.seeds || 0;
                                        const sizeBytes = t.size_bytes ? (t.size_bytes / (1024 * 1024)).toFixed(0) + " MB" : "HD";

                                        streams.push({
                                            name: "[Nuvio] EZTV " + q,
                                            title: (t.title || "Episode") + "\n" + q + " • " + sizeBytes + " • 👤 " + seeds + " seeds",
                                            infoHash: t.hash.toLowerCase(),
                                            quality: q,
                                            provider: "EZTV",
                                            size: sizeBytes
                                        });
                                    }
                                }
                            }
                        } catch (e) {
                            console.log("EZTV error: " + e.message);
                        }

                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-gogoanime",
                name = "GogoAnime HLS Stream Resolver",
                description = "Resolves direct multi-quality HLS .m3u8 streams for Anime episodes (Sub & Dub).",
                version = "2.2.0",
                author = "Anime Core",
                repoUrl = "https://gogoanime.cl",
                isEnabled = true,
                supportedTypes = "anime,series,movie",
                orderPriority = 3,
                jsCode = """
                    async function getStreams(params) {
                        const streams = [];
                        const { type, id, season, episode, kitsuId, imdbId } = params;
                        const targetId = kitsuId || imdbId || id || "";
                        const epNum = episode || 1;

                        try {
                            let searchTitle = targetId;
                            if (kitsuId || targetId.startsWith("kitsu")) {
                                const kId = targetId.replace('kitsu:', '').split(':')[0];
                                const kRes = await fetch("https://kitsu.io/api/edge/anime/" + kId);
                                if (kRes.ok) {
                                    const kData = await kRes.json();
                                    searchTitle = kData?.data?.attributes?.canonicalTitle || kData?.data?.attributes?.titles?.en || targetId;
                                }
                            }

                            if (searchTitle && searchTitle.length > 1) {
                                const searchUrl = "https://api.consumet.org/anime/gogoanime/" + encodeURIComponent(searchTitle);
                                const searchRes = await fetch(searchUrl);
                                if (searchRes.ok) {
                                    const searchData = await searchRes.json();
                                    const results = searchData?.results || [];
                                    if (results.length > 0) {
                                        const animeId = results[0].id;
                                        const epId = animeId + "-episode-" + epNum;
                                        const streamRes = await fetch("https://api.consumet.org/anime/gogoanime/watch/" + encodeURIComponent(epId));
                                        if (streamRes.ok) {
                                            const streamData = await streamRes.json();
                                            const sources = streamData?.sources || [];
                                            const headers = streamData?.headers || { "Referer": "https://s3taku.com" };

                                            for (const s of sources) {
                                                if (!s.url) continue;
                                                const q = s.quality || "default";
                                                let qualityLabel = "1080p";
                                                if (q.includes("720")) qualityLabel = "720p";
                                                else if (q.includes("480")) qualityLabel = "480p";
                                                else if (q.includes("360")) qualityLabel = "360p";
                                                else if (q.includes("1080")) qualityLabel = "1080p";

                                                streams.push({
                                                    name: "[Nuvio] GogoAnime (" + q + ")",
                                                    title: searchTitle + " - Episode " + epNum + "\n" + qualityLabel + " • Direct HLS • Fast CDN",
                                                    url: s.url,
                                                    quality: qualityLabel,
                                                    provider: "GogoAnime",
                                                    headers: headers,
                                                    isDirect: true
                                                });
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e) {
                            console.log("Anime error: " + e.message);
                        }

                        return streams;
                    }
                """.trimIndent()
            )
        )
    }
}
