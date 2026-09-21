package com.nabz.news

import android.Manifest
import android.app.*
import android.content.*
import android.net.Uri
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import coil.compose.AsyncImage
import com.nabz.news.data.*
import com.nabz.news.data.NewsRepository
import com.nabz.news.worker.NewsWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private val Ink = Color(0xFF111827)
private val Purple = Color(0xFF0E7490)
private val Surface = Color(0xFFF4F7F9)
private val Red = Color(0xFFDC2626)
private val Orange = Color(0xFFE59A23)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "nabz-news", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NewsWorker>(15, TimeUnit.MINUTES).build()
        )
        setContent { NabzApp(openNewsId = intent?.getStringExtra("news_id")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NabzApp(openNewsId: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var news by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var dollar by remember { mutableStateOf("در حال دریافت…") }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf(NewsCategory.IRAN) }
    var query by remember { mutableStateOf("") }
    var translated by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selected by remember { mutableStateOf<NewsItem?>(null) }
    var saved by remember { mutableStateOf(NewsRepository.savedIds(ctx)) }

    fun toggleSave(id: String) {
        saved = if (saved.contains(id)) saved - id else saved + id
        NewsRepository.setSavedIds(ctx, saved)
    }
    fun refresh() = scope.launch {
        loading = true
        error = null
        runCatching {
            news = NewsRepository.fetch(ctx).sortedByDescending { it.published }
            translated = news.filter { it.language != "fa" }
                .mapNotNull { n -> NewsRepository.cachedTranslation(ctx, n.id)?.let { n.id to it } }.toMap()
            dollar = NewsRepository.dollarRate()
        }.onFailure {
            error = "اتصال به منابع خبری با مشکل مواجه شد."
        }
        loading = false
    }
    LaunchedEffect(Unit) {
        news = NewsRepository.loadCachedNews(ctx)
        translated = news.filter { it.language != "fa" }
            .mapNotNull { n -> NewsRepository.cachedTranslation(ctx, n.id)?.let { n.id to it } }.toMap()
        refresh()
    }
    LaunchedEffect(news, openNewsId) {
        if (!openNewsId.isNullOrBlank()) news.firstOrNull { it.id == openNewsId }?.let { selected = it }
    }
    LaunchedEffect(news) {
        val pending = news.filter { it.language != "fa" && translated[it.id] == null }
        for (n in pending) {
            val original = n.title + "\n" + n.summary
            val t = NewsRepository.translateToPersian(original)
            if (t.isNotBlank() && t != original) {
                NewsRepository.saveTranslation(ctx, n.id, t)
                translated = translated + (n.id to t)
            }
        }
    }

    var lastBackPress by remember { mutableLongStateOf(0L) }
    BackHandler {
        when {
            selected != null -> selected = null
            tab != 0 -> tab = 0
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPress <= 2000L) {
                    (ctx as? Activity)?.finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(ctx, "برای خروج دوباره دکمه برگشت را بزنید", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Purple, onPrimary = Color.White, background = Surface, surface = Color.White)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily.SansSerif)) {
        Scaffold(
            containerColor = Surface,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(Color(0xFF0E7490), Color(0xFF164E63)))), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Article, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("روزانه", fontWeight = FontWeight.Black, fontSize = 24.sp)
                                Text("خبرهای روز، سریع و فارسی", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "به‌روزرسانی") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("خبرها") })
                    NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Bookmark, null) }, label = { Text("ذخیره‌ها") })
                    NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Notifications, null) }, label = { Text("یادآوری") })
                    NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("تنظیمات") })
                }
            }
        ) { p ->
            AnimatedContent(targetState = Pair(tab, selected), label = "screen") { state ->
                when (state.first) {
                    0 -> {
                        if (state.second == null) {
                            if (error != null && news.isEmpty()) {
                                ErrorState(
                                    Modifier.padding(p),
                                    error!!,
                                    onRetry = {
                                        scope.launch {
                                            error = null
                                            loading = true
                                            runCatching {
                                                news = NewsRepository.fetch(ctx)
                                                dollar = NewsRepository.dollarRate()
                                            }.onFailure {
                                                error = "اتصال به منابع خبری با مشکل مواجه شد."
                                            }
                                            loading = false
                                        }
                                    }
                                )
                            } else {
                                Home(
                                    Modifier.padding(p),
                                    news,
                                    loading,
                                    dollar,
                                    category,
                                    { category = it },
                                    query,
                                    { query = it },
                                    translated,
                                    { id, text ->
                                        scope.launch {
                                            val t = NewsRepository.translateToPersian(text)
                                            NewsRepository.saveTranslation(ctx, id, t)
                                            translated = translated + (id to t)
                                        }
                                    },
                                    { selected = it },
                                    saved,
                                    ::toggleSave
                                )
                            }
                        } else {
                            NewsDetailScreen(
                                Modifier.padding(p),
                                state.second!!,
                                translated[state.second!!.id],
                                saved.contains(state.second!!.id),
                                ::toggleSave,
                                { id, text ->
                                    scope.launch {
                                        val t = NewsRepository.translateToPersian(text)
                                        if (t != text) {
                                            NewsRepository.saveTranslation(ctx, id, t)
                                            translated = translated + (id to t)
                                        }
                                    }
                                },
                                { selected = null }
                            )
                        }
                    }
                    1 -> SavedScreen(
                        Modifier.padding(p),
                        news.filter { saved.contains(it.id) },
                        translated,
                        { selected = it },
                        saved,
                        ::toggleSave
                    )
                    2 -> ReminderScreen(Modifier.padding(p))
                    3 -> SettingsScreen(
                        Modifier.padding(p),
                        ctx,
                        onChanged = {
                            scope.launch {
                                news = NewsRepository.fetch(ctx)
                            }
                        }
                    )
                }
            }
        }
            }
        }
    }
}

@Composable
fun Home(mod: Modifier, news: List<NewsItem>, loading: Boolean, dollar: String, category: NewsCategory, onCategory: (NewsCategory) -> Unit, query: String, onQuery: (String) -> Unit, translated: Map<String, String>, onTranslate: (String, String) -> Unit, onOpen: (NewsItem) -> Unit, saved: Set<String>, onSave: (String) -> Unit) {
    val filtered = news.filter { n ->
        n.category == category && (query.isBlank() ||
            "${n.title} ${n.summary} ${n.source} ${translated[n.id].orEmpty()}".contains(query, ignoreCase = true))
    }
        .sortedByDescending { it.published }
    val breaking = filtered.filter { it.importance >= 80 }.take(5)
    LazyColumn(mod.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Ink), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.background(Brush.linearGradient(listOf(Ink, Color(0xFF312E81))))) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AttachMoney, null, tint = Color(0xFFC4B5FD), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(5.dp)); Text("دلار آزاد", color = Color(0xFFD1D5DB), fontSize = 13.sp)
                            Spacer(Modifier.weight(1f)); Text("آنلاین", color = Color(0xFF86EFAC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp)); Text(dollar, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
                        Text("بازار آزاد • TGJU", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                    }
                }
            }
        }
        item { OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(17.dp), leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("جستجوی خبر، منبع یا موضوع") }) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(NewsCategory.values().toList()) { c ->
                    FilterChip(
                        selected = c == category,
                        onClick = { onCategory(c) },
                        label = { Text(c.label) }
                    )
                }
            }
        }
        if (breaking.isNotEmpty()) {
            item { AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically(), exit = fadeOut()) { BreakingSection(breaking, translated, onOpen) } }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("آخرین خبرهای مهم", fontSize = 21.sp, fontWeight = FontWeight.Black); Spacer(Modifier.weight(1f)); Text("${filtered.size} خبر", color = Color.Gray, fontSize = 12.sp) } }
        if (loading) item { Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        items(filtered) { n -> NewsCard(n, translated[n.id], onTranslate, onOpen, saved.contains(n.id), onSave) }
    }
}

@Composable
fun ErrorState(mod: Modifier, message: String, onRetry: () -> Unit) {
    Column(mod.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(14.dp))
        Text("خطا در دریافت اخبار", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = Color.Gray)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("تلاش دوباره") }
    }
}

@Composable
fun BreakingSection(items: List<NewsItem>, translated: Map<String, String>, onOpen: (NewsItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Red)); Spacer(Modifier.width(7.dp)); Text("خبر فوری", color = Red, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items) { n ->
                Card(onClick = { onOpen(n) }, modifier = Modifier.width(280.dp).height(128.dp), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(n.source, color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(translated[n.id]?.substringBefore("\n") ?: n.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                        Text("اهمیت ${n.importance}/100", color = Red, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonCard() {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(150.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(0.45f).height(14.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray.copy(alpha = .45f)))
            Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray.copy(alpha = .45f)))
            Box(Modifier.fillMaxWidth(.8f).height(18.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray.copy(alpha = .45f)))
            Box(Modifier.fillMaxWidth(.35f).height(12.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray.copy(alpha = .45f)))
        }
    }
}

@Composable
fun NewsCard(n: NewsItem, fa: String?, onTranslate: (String, String) -> Unit, onOpen: (NewsItem) -> Unit, isSaved: Boolean, onSave: (String) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(120), label = "cardScale")
    val ctx = LocalContext.current
    Card(onClick = { pressed = true; onOpen(n); pressed = false }, shape = RoundedCornerShape(21.dp), modifier = Modifier.fillMaxWidth().scale(scale).animateContentSize(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column {
            if (n.image != null) AsyncImage(model = n.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(178.dp).clip(RoundedCornerShape(topStart = 21.dp, topEnd = 21.dp)))
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (n.sources.size > 1) "${n.sources.size} منبع" else n.source, color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    ImportanceBadge(n.importance)
                }
                Text(formatIranDateTime(n.published), fontSize = 10.sp, color = Color(0xFF64748B))
                Text(fa?.substringBefore("\n") ?: n.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
                Text((if (fa != null) fa.substringAfter("\n", n.summary) else n.summary).take(240), fontSize = 13.sp, color = Color(0xFF4B5563), lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (n.language != "fa" && fa == null) Button(onClick = { onTranslate(n.id, n.title + "\n" + n.summary) }, colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("ترجمه") }
                    OutlinedButton(onClick = { onSave(n.id) }) { Icon(if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null); Spacer(Modifier.width(4.dp)); Text(if (isSaved) "ذخیره شد" else "ذخیره") }
                    IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.link))) }) { Icon(Icons.Default.OpenInNew, "منبع") }
                }
            }
        }
    }
}

@Composable fun ImportanceBadge(score: Int) {
    val (label, color) = when { score >= 80 -> "فوری" to Red; score >= 65 -> "مهم" to Orange; else -> "تازه" to Color(0xFF2563EB) }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = .11f)) { Text("$label • $score", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun NewsDetailScreen(mod: Modifier, n: NewsItem, fa: String?, saved: Boolean, onSave: (String) -> Unit, onTranslate: (String, String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(mod.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowForward, "بازگشت") }; Text("جزئیات خبر", fontSize = 23.sp, fontWeight = FontWeight.Black) } }
        item { if (n.image != null) AsyncImage(model = n.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(215.dp).clip(RoundedCornerShape(22.dp))) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text(n.source, color = Purple, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); ImportanceBadge(n.importance) } }
        item { Text(formatIranDateTime(n.published), fontSize = 11.sp, color = Color(0xFF64748B)) }
        item { Text(fa?.substringBefore("\n") ?: n.title, fontSize = 25.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp) }
        item { Text(fa?.substringAfter("\n", n.summary) ?: n.summary, fontSize = 16.sp, lineHeight = 27.sp, color = Color(0xFF374151)) }
        if (n.language != "fa" && fa == null) {
            item {
                Button(onClick = { onTranslate(n.id, n.title + "\n" + n.summary) }) {
                    Icon(Icons.Default.Translate, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("ترجمه به فارسی")
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { onSave(n.id) }) { Icon(if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null); Spacer(Modifier.width(5.dp)); Text(if (saved) "ذخیره‌شده" else "ذخیره خبر") }; OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.link))) }) { Text("مشاهده منبع اصلی") } } }
        item { Text("منبع: ${n.sources.ifEmpty { listOf(n.source) }.joinToString("، ")}", color = Color.Gray, fontSize = 12.sp) }
    }
}

@Composable fun SavedScreen(mod: Modifier, news: List<NewsItem>, translated: Map<String, String>, onOpen: (NewsItem) -> Unit, saved: Set<String>, onRemove: (String) -> Unit) {
    LazyColumn(mod.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { Text("خبرهای ذخیره‌شده", fontSize = 27.sp, fontWeight = FontWeight.Black) }; if (news.isEmpty()) item { Text("هنوز خبری ذخیره نکرده‌ای.", color = Color.Gray) }; items(news.sortedByDescending { it.published }) { n -> NewsCard(n, translated[n.id], { _, _ -> }, onOpen, saved.contains(n.id), onRemove) } }
}

@Composable fun ReminderScreen(mod: Modifier) { val ctx = LocalContext.current; var hour by remember { mutableIntStateOf(20) }; var min by remember { mutableIntStateOf(0) }; var set by remember { mutableStateOf(false) }; Column(mod.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) { Text("یادآوری روزانه", fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("در ساعت انتخابی، اعلان مرور خبرهای مهم دریافت می‌کنی.", color = Color.Gray); Card(shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp)) { Text("%02d:%02d".format(hour, min), fontSize = 32.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Button(onClick = { TimePickerDialog(ctx, { _, h, m -> hour = h; min = m }, hour, min, true).show() }) { Text("انتخاب ساعت") }; Spacer(Modifier.height(8.dp)); Button(onClick = { scheduleReminder(ctx, hour, min); set = true }, modifier = Modifier.fillMaxWidth()) { Text(if (set) "یادآوری فعال شد ✓" else "فعال‌سازی یادآوری") } } }; Text("برای عملکرد بهتر اعلان‌ها، اجرای خودکار و محدودیت باتری برنامه «روزانه» را فعال نگه دار.", fontSize = 13.sp) } }

@Composable fun SettingsScreen(mod: Modifier, ctx: Context, onChanged: () -> Unit) { Column(mod.fillMaxSize().padding(18.dp)) { Text("منابع خبری", fontSize = 26.sp, fontWeight = FontWeight.Black); Spacer(Modifier.height(8.dp)); Text("منابع دلخواهت را فعال یا غیرفعال کن.", color = Color.Gray); Spacer(Modifier.height(12.dp)); NewsRepository.sourceNames().forEach { source -> var checked by remember(source) { mutableStateOf(NewsRepository.isSourceEnabled(ctx, source)) }; Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) { Text(source, modifier = Modifier.weight(1f)); Switch(checked, { checked = it; NewsRepository.setSourceEnabled(ctx, source, it); onChanged() }) } } } }

fun scheduleReminder(ctx: Context, hour: Int, minute: Int) { val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute); set(java.util.Calendar.SECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1) }; val intent = Intent(ctx, ReminderReceiver::class.java); val pi = PendingIntent.getBroadcast(ctx, 44, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi) }
