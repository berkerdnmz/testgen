package org.example.pipeline;

import java.util.List;

/**
 * Baglam asamasinin bir metot icin urettigi HER SEY.
 *
 * Bilincli olarak saf veri: icinde AST, ClassLoader veya acik akis YOK.
 * Adim 1'de (diske yazma) bu record dogrudan JSON'a serilestirilebilecek,
 * "--stage=generate" tek basina kosulabilecek.
 *
 * DIKKAT: alan tipleri ContextExtractor'in donus tiplerine gore secildi ama
 * imzalari gormedim. Derleyici uyusmazlik derse ilgili alanin tipini
 * ContextExtractor'daki donus tipiyle degistir - baska hicbir yer etkilenmez,
 * bu record yalnizca tasima yapiyor.
 */
public record MethodContext(
        List<String> types,
        List<String> voidMethods,
        List<String> calledBodies,
        List<String> constants) {
}