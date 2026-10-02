package org.example.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.io.File;

/**
 * StaticJavaParser'in is parcacigi guvenli karsiligi.
 *
 * StaticJavaParser tek bir paylasilan JavaParser ornegi tutuyor ve JavaParser
 * ornekleri is parcacigi guvenli DEGIL. Iki is parcacigi ayni anda parse
 * cagirirsa bozuk AST ya da rastgele istisna cikar - ve bunlar sessiz
 * hatalardir, cogu zaman "model kotu kod uretti" gibi gorunur.
 *
 * ThreadLocal: her is parcacigi kendi parser'ini kurar, yapilandirma ortak.
 */
public final class Parsers {

    private static final ParserConfiguration CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private static final ThreadLocal<JavaParser> LOCAL =
            ThreadLocal.withInitial(() -> new JavaParser(CONFIG));

    private Parsers() { }

    public static CompilationUnit parse(String code) {
        return unwrap(LOCAL.get().parse(code));
    }

    public static CompilationUnit parse(File file) throws java.io.FileNotFoundException {
        return unwrap(LOCAL.get().parse(file));
    }

    public static BlockStmt parseBlock(String block) {
        return unwrap(LOCAL.get().parseBlock(block));
    }

    public static ImportDeclaration parseImport(String line) {
        return unwrap(LOCAL.get().parseImport(line));
    }

    /** StaticJavaParser ile ayni sozlesme: basarisizlikta ParseProblemException. */
    private static <T> T unwrap(ParseResult<T> result) {
        if (result.isSuccessful() && result.getResult().isPresent()) {
            return result.getResult().get();
        }
        throw new ParseProblemException(result.getProblems());
    }
}