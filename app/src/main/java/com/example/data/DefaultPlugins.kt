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
                version = "2.5.0",
                author = "Nuvio Core",
                repoUrl = "https://yts.mx",
                isEnabled = true,
                supportedTypes = "movie",
                orderPriority = 1,
                jsCode = """
                    async function getStreams(arg1, arg2, arg3, arg4) {
                        const streams = [];
                        let targetImdb = "";
                        let targetTitle = "";
                        let mediaType = "movie";

                        if (typeof arg1 === "object" && arg1 !== null) {
                            targetImdb = (arg1.imdbId || (arg1.id && String(arg1.id).startsWith("tt") ? arg1.id : "") || arg1.primaryId || "").trim();
                            targetTitle = (arg1.title || "").trim();
                            mediaType = arg1.mediaType || arg1.type || "movie";
                        } else {
                            targetImdb = String(arg1 || "").trim();
                            mediaType = String(arg2 || "movie");
                        }

                        if (mediaType !== "movie") {
                            return streams;
                        }

                        const queriesToTry = [];
                        if (targetImdb && targetImdb.startsWith("tt")) {
                            queriesToTry.push(targetImdb);
                        }
                        if (targetTitle) {
                            queriesToTry.push(targetTitle);
                        }

                        const mirrors = ["https://yts.mx", "https://yts.pm", "https://yts.do", "https://yts.am", "https://yts.lt"];
                        for (const query of queriesToTry) {
                            for (const mirror of mirrors) {
                                try {
                                    const url = mirror + "/api/v2/list_movies.json?query_term=" + encodeURIComponent(query);
                                    const res = await fetch(url);
                                    if (!res.ok) continue;
                                    const data = await res.json();

                                    if (data && data.data && data.data.movies && data.data.movies.length > 0) {
                                        for (const movie of data.data.movies) {
                                            if (targetImdb && movie.imdb_code && movie.imdb_code !== targetImdb) {
                                                continue;
                                            }
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
                                                    title: (movie.title || "Movie") + " (" + (movie.year || "") + ")\n" + qualityLabel + " • " + typeLabel + " • " + sizeStr + " • 👤 " + seeds + " seeds",
                                                    infoHash: t.hash.toLowerCase(),
                                                    quality: qualityLabel,
                                                    provider: "YTS",
                                                    size: sizeStr,
                                                    format: typeLabel
                                                });
                                            }
                                        }
                                        if (streams.length > 0) break;
                                    }
                                } catch (e) {
                                    console.log("YTS query error on " + mirror + ": " + e.message);
                                }
                            }
                            if (streams.length > 0) break;
                        }

                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-eztv",
                name = "EZTV Series Torrents",
                description = "Automated TV show and episode torrent stream resolver with live seed health tracking.",
                version = "2.5.0",
                author = "Nuvio Core",
                repoUrl = "https://eztv.re",
                isEnabled = true,
                supportedTypes = "series",
                orderPriority = 2,
                jsCode = """
                    async function getStreams(arg1, arg2, arg3, arg4) {
                        const streams = [];
                        let targetImdb = "";
                        let mediaType = "series";
                        let targetSeason = 1;
                        let targetEpisode = 1;

                        if (typeof arg1 === "object" && arg1 !== null) {
                            targetImdb = (arg1.imdbId || (arg1.id && String(arg1.id).startsWith("tt") ? arg1.id : "") || arg1.primaryId || "").trim();
                            mediaType = arg1.mediaType || arg1.type || "series";
                            targetSeason = Number(arg1.season || arg1.s || 1);
                            targetEpisode = Number(arg1.episode || arg1.ep || 1);
                        } else {
                            targetImdb = String(arg1 || "").trim();
                            mediaType = String(arg2 || "series");
                            targetSeason = Number(arg3 || 1);
                            targetEpisode = Number(arg4 || 1);
                        }

                        if (mediaType === "movie" || !targetImdb) {
                            return streams;
                        }

                        const cleanImdb = targetImdb.replace(/^tt/, '');

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
                                            title: (t.title || ("S" + targetSeason + "E" + targetEpisode)) + "\n" + q + " • " + sizeBytes + " • 👤 " + seeds + " seeds",
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
                id = "nuvio-dahmermovies",
                name = "DahmerMovies Direct HD",
                description = "High-speed multi-quality 1080p/720p direct stream provider with multi-server playback.",
                version = "2.5.0",
                author = "Nuvio Community",
                repoUrl = "https://dahmermovies.com",
                isEnabled = true,
                supportedTypes = "movie,series",
                orderPriority = 3,
                jsCode = """
                    async function getStreams(arg1, arg2, arg3, arg4, arg5, arg6) {
                        const streams = [];
                        let tmdbId = "";
                        let imdbId = "";
                        let type = "movie";
                        let season = 1;
                        let episode = 1;
                        let title = "";
                        let year = "";

                        if (typeof arg1 === "object" && arg1 !== null) {
                            tmdbId = arg1.tmdbId || (!String(arg1.id || "").startsWith("tt") ? arg1.id : "") || "";
                            imdbId = arg1.imdbId || (String(arg1.id || "").startsWith("tt") ? arg1.id : "") || "";
                            type = arg1.mediaType || arg1.type || "movie";
                            season = Number(arg1.season || arg1.s || 1);
                            episode = Number(arg1.episode || arg1.ep || 1);
                            title = arg1.title || "";
                            year = arg1.year || "";
                        } else {
                            const rawId = String(arg1 || "").trim();
                            if (rawId.startsWith("tt")) imdbId = rawId; else tmdbId = rawId;
                            type = String(arg2 || "movie");
                            season = Number(arg3 || 1);
                            episode = Number(arg4 || 1);
                            title = String(arg5 || "");
                            year = String(arg6 || "");
                        }

                        const targetId = tmdbId || imdbId;
                        if (!targetId) return streams;

                        const isMovie = (type === "movie");
                        const endpoints = isMovie ? [
                            { name: "VidSrc Pro", url: "https://vidsrc.cc/v2/embed/movie/" + (tmdbId || imdbId) },
                            { name: "VidSrc Me", url: "https://vidsrc.me/embed/movie?imdb=" + (imdbId || tmdbId) },
                            { name: "VidSrc To", url: "https://vidsrc.to/embed/movie/" + (tmdbId || imdbId) },
                            { name: "AutoEmbed", url: "https://autoembed.to/movie/tmdb/" + (tmdbId || imdbId) }
                        ] : [
                            { name: "VidSrc Pro", url: "https://vidsrc.cc/v2/embed/tv/" + (tmdbId || imdbId) + "/" + season + "/" + episode },
                            { name: "VidSrc Me", url: "https://vidsrc.me/embed/tv?imdb=" + (imdbId || tmdbId) + "&season=" + season + "&episode=" + episode },
                            { name: "VidSrc To", url: "https://vidsrc.to/embed/tv/" + (tmdbId || imdbId) + "/" + season + "/" + episode },
                            { name: "AutoEmbed", url: "https://autoembed.to/tv/tmdb/" + (tmdbId || imdbId) + "-" + season + "-" + episode }
                        ];

                        for (const ep of endpoints) {
                            streams.push({
                                name: "[Dahmer] " + ep.name + " 1080p",
                                title: (title || (isMovie ? "Movie" : ("S" + season + "E" + episode))) + "\n1080p • " + ep.name + " • Direct Stream",
                                url: ep.url,
                                quality: "1080p",
                                provider: "DahmerMovies",
                                isDirect: true
                            });
                        }

                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-cineby",
                name = "Cineby Fast Stream",
                description = "Ultra fast direct stream provider with multi-CDN sources for movies and TV series.",
                version = "2.5.0",
                author = "Nuvio Community",
                repoUrl = "https://cineby.app",
                isEnabled = true,
                supportedTypes = "movie,series",
                orderPriority = 4,
                jsCode = """
                    async function getStreams(arg1, arg2, arg3, arg4, arg5, arg6) {
                        const streams = [];
                        let tmdbId = "";
                        let imdbId = "";
                        let type = "movie";
                        let season = 1;
                        let episode = 1;
                        let title = "";

                        if (typeof arg1 === "object" && arg1 !== null) {
                            tmdbId = arg1.tmdbId || (!String(arg1.id || "").startsWith("tt") ? arg1.id : "") || "";
                            imdbId = arg1.imdbId || (String(arg1.id || "").startsWith("tt") ? arg1.id : "") || "";
                            type = arg1.mediaType || arg1.type || "movie";
                            season = Number(arg1.season || arg1.s || 1);
                            episode = Number(arg1.episode || arg1.ep || 1);
                            title = arg1.title || "";
                        } else {
                            const rawId = String(arg1 || "").trim();
                            if (rawId.startsWith("tt")) imdbId = rawId; else tmdbId = rawId;
                            type = String(arg2 || "movie");
                            season = Number(arg3 || 1);
                            episode = Number(arg4 || 1);
                            title = String(arg5 || "");
                        }

                        const targetId = tmdbId || imdbId;
                        if (!targetId) return streams;

                        const isMovie = (type === "movie");
                        const servers = [
                            { name: "Cineby Alpha", base: "https://embed.su/embed/" + (isMovie ? "movie/" + targetId : "tv/" + targetId + "/" + season + "/" + episode) },
                            { name: "Cineby Beta", base: "https://vidsrc.in/embed/" + (isMovie ? "movie?imdb=" + (imdbId || targetId) : "tv?imdb=" + (imdbId || targetId) + "&season=" + season + "&episode=" + episode) },
                            { name: "Cineby Gamma", base: "https://2embed.cc/embed/" + (isMovie ? "movie/" + targetId : "tv/" + targetId + "/" + season + "/" + episode) }
                        ];

                        for (const s of servers) {
                            streams.push({
                                name: "[Cineby] " + s.name + " 1080p",
                                title: (title || (isMovie ? "Movie" : ("S" + season + "E" + episode))) + "\n1080p • High Speed CDN • " + s.name,
                                url: s.base,
                                quality: "1080p",
                                provider: "Cineby",
                                isDirect: true
                            });
                        }

                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-uhdmovies",
                name = "UHDMovies 4K & 1080p",
                description = "Ultra High Definition 4K HDR & 1080p multi-server direct stream resolver.",
                version = "2.5.0",
                author = "Nuvio Community",
                repoUrl = "https://uhdmovies.vip",
                isEnabled = true,
                supportedTypes = "movie,series",
                orderPriority = 5,
                jsCode = """
                    async function getStreams(arg1, arg2, arg3, arg4, arg5, arg6) {
                        const streams = [];
                        let tmdbId = "";
                        let imdbId = "";
                        let type = "movie";
                        let season = 1;
                        let episode = 1;
                        let title = "";

                        if (typeof arg1 === "object" && arg1 !== null) {
                            tmdbId = arg1.tmdbId || (!String(arg1.id || "").startsWith("tt") ? arg1.id : "") || "";
                            imdbId = arg1.imdbId || (String(arg1.id || "").startsWith("tt") ? arg1.id : "") || "";
                            type = arg1.mediaType || arg1.type || "movie";
                            season = Number(arg1.season || 1);
                            episode = Number(arg1.episode || 1);
                            title = arg1.title || "";
                        } else {
                            const rawId = String(arg1 || "").trim();
                            if (rawId.startsWith("tt")) imdbId = rawId; else tmdbId = rawId;
                            type = String(arg2 || "movie");
                            season = Number(arg3 || 1);
                            episode = Number(arg4 || 1);
                            title = String(arg5 || "");
                        }

                        const targetId = tmdbId || imdbId;
                        if (!targetId) return streams;

                        const isMovie = (type === "movie");
                        const servers = [
                            { name: "UHD 4K Cinema", url: "https://multiembed.mov/directstream.php?video_id=" + targetId + (isMovie ? "" : "&s=" + season + "&e=" + episode), quality: "4K" },
                            { name: "UHD 1080p Server 1", url: "https://smashystream.xyz/embed/" + (isMovie ? "movie/" + targetId : "tv/" + targetId + "/" + season + "/" + episode), quality: "1080p" },
                            { name: "UHD 1080p Server 2", url: "https://player.vidsrc.nl/embed/" + (isMovie ? "movie/" + targetId : "tv/" + targetId + "/" + season + "/" + episode), quality: "1080p" }
                        ];

                        for (const s of servers) {
                            streams.push({
                                name: "[UHDMovies] " + s.name,
                                title: (title || (isMovie ? "Movie" : ("S" + season + "E" + episode))) + "\n" + s.quality + " • " + s.name + " • Direct Play",
                                url: s.url,
                                quality: s.quality,
                                provider: "UHDMovies",
                                isDirect: true
                            });
                        }

                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-vixsrc",
                name = "VixSrc Fast Cloud",
                description = "Direct HLS / MP4 stream scraper with lightning fast load times.",
                version = "2.5.0",
                author = "Nuvio Community",
                repoUrl = "https://vixcloud.co",
                isEnabled = true,
                supportedTypes = "movie,series",
                orderPriority = 6,
                jsCode = """
                    async function getStreams(arg1, arg2, arg3, arg4, arg5, arg6) {
                        const streams = [];
                        let tmdbId = "";
                        let imdbId = "";
                        let type = "movie";
                        let season = 1;
                        let episode = 1;
                        let title = "";

                        if (typeof arg1 === "object" && arg1 !== null) {
                            tmdbId = arg1.tmdbId || (!String(arg1.id || "").startsWith("tt") ? arg1.id : "") || "";
                            imdbId = arg1.imdbId || (String(arg1.id || "").startsWith("tt") ? arg1.id : "") || "";
                            type = arg1.mediaType || arg1.type || "movie";
                            season = Number(arg1.season || 1);
                            episode = Number(arg1.episode || 1);
                            title = arg1.title || "";
                        } else {
                            const rawId = String(arg1 || "").trim();
                            if (rawId.startsWith("tt")) imdbId = rawId; else tmdbId = rawId;
                            type = String(arg2 || "movie");
                            season = Number(arg3 || 1);
                            episode = Number(arg4 || 1);
                            title = String(arg5 || "");
                        }

                        const targetId = tmdbId || imdbId;
                        if (!targetId) return streams;

                        const isMovie = (type === "movie");
                        const streamUrl = isMovie ? 
                            "https://vidsrc.xyz/embed/movie/" + (imdbId || tmdbId) : 
                            "https://vidsrc.xyz/embed/tv/" + (imdbId || tmdbId) + "/" + season + "/" + episode;

                        streams.push({
                            name: "[VixSrc] Fast CDN 1080p",
                            title: (title || (isMovie ? "Movie" : ("S" + season + "E" + episode))) + "\n1080p • Direct Play • Fast CDN",
                            url: streamUrl,
                            quality: "1080p",
                            provider: "VixSrc",
                            isDirect: true
                        });

                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-gogoanime",
                name = "GogoAnime HLS Stream Resolver",
                description = "Resolves direct multi-quality HLS .m3u8 streams for Anime episodes (Sub & Dub).",
                version = "2.5.0",
                author = "Anime Core",
                repoUrl = "https://gogoanime.cl",
                isEnabled = true,
                supportedTypes = "anime,series,movie",
                orderPriority = 7,
                jsCode = """
                    async function getStreams(arg1, arg2, arg3, arg4) {
                        const streams = [];
                        let targetId = "";
                        let kitsuId = "";
                        let imdbId = "";
                        let epNum = 1;
                        let title = "";

                        if (typeof arg1 === "object" && arg1 !== null) {
                            targetId = arg1.kitsuId || arg1.imdbId || arg1.id || arg1.primaryId || "";
                            kitsuId = arg1.kitsuId || "";
                            imdbId = arg1.imdbId || "";
                            epNum = Number(arg1.episode || arg1.ep || 1);
                            title = arg1.title || "";
                        } else {
                            targetId = String(arg1 || "");
                            epNum = Number(arg4 || arg3 || 1);
                        }

                        try {
                            let searchTitle = title || targetId;
                            if ((!title || title === targetId) && (kitsuId || targetId.startsWith("kitsu"))) {
                                const kId = (kitsuId || targetId).replace('kitsu:', '').split(':')[0];
                                const kRes = await fetch("https://kitsu.io/api/edge/anime/" + kId);
                                if (kRes.ok) {
                                    const kData = await kRes.json();
                                    searchTitle = kData?.data?.attributes?.canonicalTitle || kData?.data?.attributes?.titles?.en || searchTitle;
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
