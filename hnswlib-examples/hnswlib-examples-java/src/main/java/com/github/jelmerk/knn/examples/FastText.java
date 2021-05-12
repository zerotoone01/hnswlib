package com.github.jelmerk.knn.examples;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import com.github.jelmerk.knn.DistanceFunctions;
import com.github.jelmerk.knn.Index;
import com.github.jelmerk.knn.SearchResult;
import com.github.jelmerk.knn.hnsw.HnswIndex;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import static com.github.jelmerk.knn.util.VectorUtils.normalize;

/**
 * Example application that downloads the english fast-text word vectors, inserts them into an hnsw index and lets
 * you query them.
 */
public class FastText {

    private static final String WORDS_FILE_URL = "https://dl.fbaipublicfiles.com/fasttext/vectors-crawl/cc.en.300.vec.gz";

//    private static final Path TMP_PATH = Paths.get(System.getProperty("java.io.tmpdir"));
    private static final Path TMP_PATH = Paths.get(System.getProperty("user.dir"));

    public static void main(String[] args) throws Exception {

        Path file = TMP_PATH.resolve("cc.en.300.vec.gz");

        if (!Files.exists(file)) {
            downloadFile(WORDS_FILE_URL, file);
        } else {
            System.out.printf("Input file already downloaded. Using %s%n", file);
        }

        List<Word> words = loadWordVectors(file);

        System.out.println("Constructing index.");

        HnswIndex<String, float[], Word, Float> hnswIndex = HnswIndex
                //对象的维度，距离函数，一个index最大能容纳的元素数量
                .newBuilder(300, DistanceFunctions.FLOAT_INNER_PRODUCT, words.size())
                //每个点需要与图中其他的点建立的连接数
                .withM(16)
                //需要返回的近邻数量，用于确定新插入点的位置
                .withEf(200)
                //动态候选元素集合大小，要大于等于ef，值越大精确度越高，耗时越长
                .withEfConstruction(200)
                .build();

        long start = System.currentTimeMillis();

        hnswIndex.addAll(words, (workDone, max) -> System.out.printf("Added %d out of %d words to the index.%n", workDone, max));

        long end = System.currentTimeMillis();

        long duration = end - start;

        System.out.printf("Creating index with %d words took %d millis which is %d minutes.%n", hnswIndex.size(), duration, MILLISECONDS.toMinutes(duration));

        //顶层索引视图
        Index<String, float[], Word, Float> groundTruthIndex = hnswIndex.asExactIndex();

        Console console = System.console();

        int k = 10;

        while (true) {
            System.out.println("Enter an english word : ");

            String input = console.readLine();

            //针对已入库的数据，且id已知，可以用findNeighbors()方法处理
            //精确查找算法
            List<SearchResult<Word, Float>> approximateResults = hnswIndex.findNeighbors(input, k);

            //通过顶层索引查找算法
            List<SearchResult<Word, Float>> groundTruthResults = groundTruthIndex.findNeighbors(input, k);

            //针对直接特征提取的图片，没有入库的数据，用特征向量来获取
            //hnswIndex.findNearest();
            //groundTruthIndex.findNearest();

            //这两种查找算法的效率差别有大？

            System.out.println("Most similar words found using HNSW index : %n%n");

            for (SearchResult<Word, Float> result : approximateResults) {
                System.out.printf("%s %.4f%n", result.item().id(), result.distance());
            }

            System.out.printf("%nMost similar words found using exact index: %n%n");

            for (SearchResult<Word, Float> result : groundTruthResults) {
                System.out.printf("%s %.4f%n", result.item().id(), result.distance());
            }

            int correct = groundTruthResults.stream().mapToInt(r -> approximateResults.contains(r) ? 1 : 0).sum();

            System.out.printf("%nAccuracy : %.4f%n%n", correct / (double) groundTruthResults.size());
        }
    }

    private static void downloadFile(String url, Path path) throws IOException {
        System.out.printf("Downloading %s to %s. This may take a while.%n", url, path);

        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, path);
        }
    }
    private static List<Word> loadWordVectors(Path path) throws IOException {
        System.out.printf("Loading words from %s%n", path);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .skip(1)
                    .map(line -> {
                        String[] tokens = line.split(" ");

                        String word = tokens[0];

                        float[] vector = new float[tokens.length - 1];
                        for (int i = 1; i < tokens.length - 1; i++) {
                            vector[i] = Float.parseFloat(tokens[i]);
                        }

                        return new Word(word, normalize(vector)); // normalize the vector so we can do inner product search
                    })
                    .collect(Collectors.toList());
        }
    }

}
