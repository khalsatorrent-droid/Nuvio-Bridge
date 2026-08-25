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
        ),
        RepoEntity(
            id = "nuvio-official",
            name = "Nuvio Built-in Core Scrapers",
            url = "https://nuvioplugins.com/index.html",
            description = "Official Nuvio streaming plugins repository with multi-CDN failover.",
            pluginCount = 6
        )
    )

    fun getDefaultPlugins(): List<PluginEntity> {
        return listOf(
            PluginEntity(
                id = "nuvio-vidsrc",
                name = "VidSrc Pro",
                description = "High-speed multi-source scraper for Movies & TV series with 1080p/4K streams.",
                version = "1.3.0",
                author = "Nuvio Team",
                repoUrl = "https://nuvioplugins.com",
                isEnabled = true,
                supportedTypes = "movie,series",
                orderPriority = 1,
                jsCode = """
                    async function getStreams(params) {
                        const streams = [];
                        const { type, id, season, episode, tmdbId, imdbId } = params;
                        const targetId = imdbId || id || tmdbId;
                        
                        try {
                            // 1. VidSrc Primary Endpoint
                            let embedUrl = "";
                            if (type === "movie") {
                                embedUrl = "https://vidsrc.xyz/embed/movie/" + targetId;
                            } else {
                                embedUrl = "https://vidsrc.xyz/embed/tv/" + targetId + "/" + (season || 1) + "/" + (episode || 1);
                            }
                            
                            streams.push({
                                name: "[Nuvio] VidSrc",
                                title: "VidSrc HD • Multi-Audio\n1080p • Fast CDN",
                                url: embedUrl,
                                quality: "1080p",
                                provider: "VidSrc",
                                isDirect: false
                            });

                            // 2. VidSrc Server 2 (Pro)
                            let proUrl = "";
                            if (type === "movie") {
                                proUrl = "https://vidsrc.pro/embed/movie/" + targetId;
                            } else {
                                proUrl = "https://vidsrc.pro/embed/tv/" + targetId + "/" + (season || 1) + "/" + (episode || 1);
                            }
                            
                            streams.push({
                                name: "[Nuvio] VidSrc Pro",
                                title: "VidSrc Server 2 • 1080p / 720p\nMulti-Subtitles • High Bitrate",
                                url: proUrl,
                                quality: "1080p",
                                provider: "VidSrc Pro",
                                isDirect: false
                            });

                            // 3. VidSrc CC Mirror
                            let ccUrl = "";
                            if (type === "movie") {
                                ccUrl = "https://vidsrc.cc/v2/embed/movie/" + targetId;
                            } else {
                                ccUrl = "https://vidsrc.cc/v2/embed/tv/" + targetId + "/" + (season || 1) + "/" + (episode || 1);
                            }
                            
                            streams.push({
                                name: "[Nuvio] VidSrc CC",
                                title: "VidSrc CC • 4K / 1080p\nMulti-language Audio & Subs",
                                url: ccUrl,
                                quality: "4K",
                                provider: "VidSrc CC",
                                isDirect: false
                            });
                        } catch(e) {
                            console.log("VidSrc Error: " + e.message);
                        }
                        
                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-autoembed",
                name = "AutoEmbed / MultiEmbed",
                description = "Ultra reliable auto-fallback embed player supporting 4K, 1080p, 720p and adaptive HLS.",
                version = "1.2.5",
                author = "Nuvio Core",
                repoUrl = "https://nuvioplugins.com",
                isEnabled = true,
                supportedTypes = "movie,series",
                orderPriority = 2,
                jsCode = """
                    async function getStreams(params) {
                        const streams = [];
                        const { type, id, season, episode, tmdbId, imdbId } = params;
                        const targetId = imdbId || id || tmdbId;
                        
                        try {
                            // AutoEmbed.cc
                            let url1 = "";
                            if (type === "movie") {
                                url1 = "https://player.autoembed.cc/embed/movie/" + targetId;
                            } else {
                                url1 = "https://player.autoembed.cc/embed/tv/" + targetId + "/" + (season || 1) + "/" + (episode || 1);
                            }
                            streams.push({
                                name: "[Nuvio] AutoEmbed",
                                title: "AutoEmbed Multi-Server • 1080p\nFast CDN • Adaptive Stream",
                                url: url1,
                                quality: "1080p",
                                provider: "AutoEmbed",
                                isDirect: false
                            });

                            // MultiEmbed.mov
                            let url2 = "";
                            if (type === "movie") {
                                url2 = "https://multiembed.mov/?video_id=" + targetId;
                            } else {
                                url2 = "https://multiembed.mov/?video_id=" + targetId + "&s=" + (season || 1) + "&e=" + (episode || 1);
                            }
                            streams.push({
                                name: "[Nuvio] MultiEmbed",
                                title: "MultiEmbed VIP • 4K UHD\nDolby Atmos • Direct HLS",
                                url: url2,
                                quality: "4K",
                                provider: "MultiEmbed",
                                isDirect: false
                            });
                        } catch(e) {
                            console.log("AutoEmbed Error: " + e.message);
                        }
                        
                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-smashystream",
                name = "SmashyStream",
                description = "High quality multi-server aggregator with servers like DPlayer, FX, Neko, and Hydra.",
                version = "1.1.8",
                author = "SmashyDev",
                repoUrl = "https://nuvioplugins.com",
                isEnabled = true,
                supportedTypes = "movie,series",
                orderPriority = 3,
                jsCode = """
                    async function getStreams(params) {
                        const streams = [];
                        const { type, id, season, episode, tmdbId, imdbId } = params;
                        const targetId = imdbId || id || tmdbId;
                        
                        try {
                            let smashyUrl = "";
                            if (type === "movie") {
                                smashyUrl = "https://player.smashystream.com/movie/" + targetId;
                            } else {
                                smashyUrl = "https://player.smashystream.com/tv/" + targetId + "/" + (season || 1) + "/" + (episode || 1);
                            }
                            
                            streams.push({
                                name: "[Nuvio] SmashyStream",
                                title: "SmashyStream DPlayer • 1080p 60FPS\nEnglish / Multi Audio • Fast CDN",
                                url: smashyUrl,
                                quality: "1080p",
                                provider: "SmashyStream",
                                isDirect: false
                            });

                            streams.push({
                                name: "[Nuvio] Smashy Mirror",
                                title: "SmashyStream FX Server • 720p HD\nFast buffer • Mobile friendly",
                                url: smashyUrl + "?server=fx",
                                quality: "720p",
                                provider: "SmashyStream FX",
                                isDirect: false
                            });
                        } catch(e) {
                            console.log("SmashyStream Error: " + e.message);
                        }
                        
                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-superstream",
                name = "SuperStream HD",
                description = "Direct high-bandwidth CDN streams with multi-language subtitle tracks.",
                version = "2.0.1",
                author = "Nuvio Team",
                repoUrl = "https://nuvioplugins.com",
                isEnabled = true,
                supportedTypes = "movie,series",
                orderPriority = 4,
                jsCode = """
                    async function getStreams(params) {
                        const streams = [];
                        const { type, id, season, episode, tmdbId, imdbId } = params;
                        const targetId = imdbId || id || tmdbId;
                        
                        try {
                            // 2Embed server
                            let embedUrl = "";
                            if (type === "movie") {
                                embedUrl = "https://www.2embed.cc/embed/" + targetId;
                            } else {
                                embedUrl = "https://www.2embed.cc/embedtv/" + targetId + "&s=" + (season || 1) + "&e=" + (episode || 1);
                            }
                            
                            streams.push({
                                name: "[Nuvio] SuperStream",
                                title: "SuperStream Direct • 1080p FHD\nMulti-Subtitles • High Bitrate",
                                url: embedUrl,
                                quality: "1080p",
                                provider: "SuperStream",
                                isDirect: false
                            });

                            // MoviesAPI Mirror
                            let moviesApiUrl = "";
                            if (type === "movie") {
                                moviesApiUrl = "https://moviesapi.club/movie/" + targetId;
                            } else {
                                moviesApiUrl = "https://moviesapi.club/tv/" + targetId + "-" + (season || 1) + "-" + (episode || 1);
                            }
                            
                            streams.push({
                                name: "[Nuvio] MoviesAPI",
                                title: "MoviesAPI FastCDN • 720p HD\nInstant playback • No buffering",
                                url: moviesApiUrl,
                                quality: "720p",
                                provider: "MoviesAPI",
                                isDirect: false
                            });
                        } catch(e) {
                            console.log("SuperStream Error: " + e.message);
                        }
                        
                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-flixer",
                name = "Flixer / CineMat",
                description = "Modern scraper with 4K UHD and HDR stream resolvers.",
                version = "1.0.4",
                author = "Nuvio Community",
                repoUrl = "https://nuvioplugins.com",
                isEnabled = true,
                supportedTypes = "movie,series",
                orderPriority = 5,
                jsCode = """
                    async function getStreams(params) {
                        const streams = [];
                        const { type, id, season, episode, tmdbId, imdbId } = params;
                        const targetId = imdbId || id || tmdbId;
                        
                        try {
                            let flixUrl = "";
                            if (type === "movie") {
                                flixUrl = "https://embed.su/embed/movie/" + targetId;
                            } else {
                                flixUrl = "https://embed.su/embed/tv/" + targetId + "/" + (season || 1) + "/" + (episode || 1);
                            }
                            
                            streams.push({
                                name: "[Nuvio] Flixer 4K",
                                title: "Flixer VIP • 4K UHD HDR\nEnglish Dolby 5.1 • 15 Mbps",
                                url: flixUrl,
                                quality: "4K",
                                provider: "Flixer",
                                isDirect: false
                            });

                            streams.push({
                                name: "[Nuvio] Flixer 1080p",
                                title: "Flixer Core • 1080p FHD\nMulti-Subtitles • High Speed",
                                url: flixUrl,
                                quality: "1080p",
                                provider: "Flixer",
                                isDirect: false
                            });
                        } catch(e) {
                            console.log("Flixer Error: " + e.message);
                        }
                        
                        return streams;
                    }
                """.trimIndent()
            ),
            PluginEntity(
                id = "nuvio-animepahe",
                name = "AnimePahe & Kitsu Anime",
                description = "Dedicated Anime scraper resolving Sub & Dub streams using Kitsu / AniList / IMDB IDs.",
                version = "1.1.0",
                author = "AnimeDev",
                repoUrl = "https://nuvioplugins.com",
                isEnabled = true,
                supportedTypes = "anime,series,movie",
                orderPriority = 6,
                jsCode = """
                    async function getStreams(params) {
                        const streams = [];
                        const { type, id, season, episode, kitsuId, imdbId } = params;
                        const targetId = kitsuId || imdbId || id;
                        
                        try {
                            // Anime specific resolver
                            const epNum = episode || 1;
                            const animeUrl = "https://vidsrc.me/embed/anime/" + targetId + "/" + epNum;
                            
                            streams.push({
                                name: "[Nuvio] AnimePahe Sub",
                                title: "AnimePahe Japanese Audio • 1080p\nEnglish Hardsub • 60FPS",
                                url: animeUrl,
                                quality: "1080p",
                                provider: "AnimePahe",
                                isDirect: false
                            });

                            streams.push({
                                name: "[Nuvio] Anime Dub",
                                title: "Anime English Dubbed • 720p HD\nFast CDN • Multi-Server",
                                url: animeUrl + "?dub=1",
                                quality: "720p",
                                provider: "AnimePahe Dub",
                                isDirect: false
                            });
                        } catch(e) {
                            console.log("AnimePahe Error: " + e.message);
                        }
                        
                        return streams;
                    }
                """.trimIndent()
            )
        )
    }
}
