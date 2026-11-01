package com.example.arfas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object ArogyaDesign {
    val colors=lightColorScheme(
        primary=Color(0xFF086C63),onPrimary=Color.White,
        primaryContainer=Color(0xFFD7F3EB),onPrimaryContainer=Color(0xFF123C35),
        secondary=Color(0xFF55736B),secondaryContainer=Color(0xFFE4EEE9),
        background=Color(0xFFF6F8F4),onBackground=Color(0xFF18362F),
        surface=Color(0xFFFFFFFF),onSurface=Color(0xFF18362F),
        surfaceVariant=Color(0xFFEAF0E9),onSurfaceVariant=Color(0xFF52675E),
        outline=Color(0xFF85998F),error=Color(0xFFAF342D),errorContainer=Color(0xFFFFE7E1))
}

@Composable
fun ArogyaHero(hindi:Boolean,onStart:()->Unit,enabled:Boolean,drafts:Int) {
    Column(Modifier.fillMaxWidth().background(
        Brush.linearGradient(listOf(Color(0xFF075B54),Color(0xFF177F71))),RoundedCornerShape(28.dp))
        .padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(if(hindi) "आपके साथ, हर घर तक" else "CARE THAT GOES WITH YOU",color=Color(0xFFB8E8D7),style=MaterialTheme.typography.labelLarge)
        Text(if(hindi) "बोलिए। जाँचिए।\nविज़िट सहेजिए।" else "Speak. Review.\nMake every visit count.",color=Color.White,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text(if(hindi) "अपनी भाषा में बात दर्ज करें। AI नोट व्यवस्थित करेगा—आपकी जाँच के बाद।" else "Record in your own words. AI organises the notes. You review and confirm.",color=Color(0xFFE0F3EA))
        Button(onClick=onStart,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),
            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFDDF5A1),contentColor=Color(0xFF163C2D))) {
            Text(if(hindi) "नई विज़िट शुरू करें" else "Start a home visit",fontWeight=FontWeight.Bold)
        }
        if(drafts>0) Text(if(hindi) "$drafts अधूरी विज़िट नीचे उपलब्ध हैं" else "$drafts unfinished visit${if(drafts==1) "" else "s"} available below",color=Color(0xFFDBEFE6),style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ArogyaSection(kicker:String,title:String,detail:String="") {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp),modifier=Modifier.padding(top=8.dp,bottom=8.dp)) {
        Text(kicker,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold)
        Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        if(detail.isNotBlank()) Text(detail,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
    }
}
