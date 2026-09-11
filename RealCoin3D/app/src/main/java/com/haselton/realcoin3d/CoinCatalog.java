package com.haselton.realcoin3d;

import android.graphics.Color;
import java.util.*;

public final class CoinCatalog {
    private CoinCatalog() {}
    public static List<CoinDefinition> starterCoins() {
        List<CoinDefinition> c = new ArrayList<>();
        c.add(new CoinDefinition("quarter","US Quarter","US COINS","Modern","Cupronickel",24.26,1.75,5.67,"LIBERTY","¼","Modern U.S.-inspired quarter simulation.",Color.rgb(185,190,194),Color.rgb(244,246,247),Color.rgb(74,78,82)));
        c.add(new CoinDefinition("morgan","Morgan Silver Dollar","US CLASSICS","1878–1921 type","Silver",38.1,2.4,26.73,"LIBERTY","EAGLE","Collector-favorite silver dollar type.",Color.rgb(174,181,187),Color.rgb(250,251,252),Color.rgb(63,69,74)));
        c.add(new CoinDefinition("walking","Walking Liberty Half","US CLASSICS","1916–1947 type","Silver",30.6,2.15,12.5,"LIBERTY","EAGLE","Walking Liberty-inspired historical simulation.",Color.rgb(179,184,188),Color.rgb(247,248,249),Color.rgb(66,70,73)));
        c.add(new CoinDefinition("buffalo","Buffalo Nickel","US CLASSICS","1913–1938 type","Cupronickel",21.21,1.95,5.0,"LIBERTY","BISON","Buffalo Nickel-inspired historical simulation.",Color.rgb(157,160,157),Color.rgb(220,223,219),Color.rgb(61,64,61)));
        c.add(new CoinDefinition("saint","Saint-Gaudens Double Eagle","US CLASSICS","1907–1933 type","Gold",34.0,2.41,33.44,"LIBERTY","EAGLE","Gold double-eagle inspired historical simulation.",Color.rgb(210,156,45),Color.rgb(255,222,111),Color.rgb(104,67,10)));
        c.add(new CoinDefinition("owl","Athenian Owl Tetradrachm","ANCIENT GREEK","5th c. BCE","Silver",25.0,4.0,17.2,"ATHENA","OWL","Irregular-flan simulation of the famous Athenian owl type.",Color.rgb(164,161,151),Color.rgb(226,222,207),Color.rgb(72,70,64)));
        c.add(new CoinDefinition("alexander","Alexander Tetradrachm","ANCIENT GREEK","4th c. BCE","Silver",27.0,3.8,17.1,"HERAKLES","ZEUS","Alexander-era tetradrachm inspired simulation.",Color.rgb(170,167,157),Color.rgb(232,228,213),Color.rgb(75,72,66)));
        c.add(new CoinDefinition("denarius","Roman Denarius","ANCIENT ROMAN","1st–2nd c. CE style","Silver",19.0,2.0,3.6,"CAESAR","SPQR","General Roman denarius-inspired historical simulation.",Color.rgb(166,163,154),Color.rgb(228,225,214),Color.rgb(70,68,63)));
        c.add(new CoinDefinition("eightreales","Spanish Piece of Eight","WORLD","Spanish colonial type","Silver",39.0,2.5,27.0,"CAROLUS","8R","8 reales / piece-of-eight inspired simulation.",Color.rgb(173,178,179),Color.rgb(239,242,241),Color.rgb(68,73,74)));
        c.add(new CoinDefinition("sovereign","British Sovereign","WORLD","Historic type","Gold",22.05,1.52,7.99,"CROWN","DRAGON","British sovereign-inspired historical simulation.",Color.rgb(211,158,48),Color.rgb(255,225,120),Color.rgb(105,69,14)));
        c.add(new CoinDefinition("bitcoin","Bitcoin Token","DIGITAL","Contemporary fantasy token","Gold-tone",40.0,3.2,28.0,"₿","BLOCK","Original physical interpretation of a Bitcoin-themed token.",Color.rgb(221,154,25),Color.rgb(255,225,89),Color.rgb(107,63,4)));
        return c;
    }
}
