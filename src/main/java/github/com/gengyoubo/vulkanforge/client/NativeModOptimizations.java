package github.com.gengyoubo.vulkanforge.client;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.*;

/** Native-mode addon optimizations preserve the addons' comparator and stable selection semantics. */
public final class NativeModOptimizations {
    private record Candidate<T>(T value,int order) { }
    private static Field culling,limit;
    private static long calls,candidates,selected,cpuNs;
    private static final boolean PROFILE=Boolean.getBoolean("vulkanforge.nativeProfile");
    private NativeModOptimizations() { }
    public static <T> void sortLimitedEntities(List<T> values,Comparator<? super T> comparator){
        long start=PROFILE?System.nanoTime():0;
        try{
            if(culling==null){Class<?> config=Class.forName("me.srrapero720.chloride.ChlorideConfig");culling=config.getField("culling");limit=culling.getType().getField("entityLimit");}
            int maximum=limit.getInt(culling.get(null));calls++;candidates+=values.size();selectStable(values,comparator,maximum);selected+=values.size();
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Unsupported Chloride culling config",error);}
        finally{if(PROFILE)cpuNs+=System.nanoTime()-start;}
    }
    /** Exactly the first k entries of Java's stable full sort, including comparator ties and original iteration order. */
    static <T> void selectStable(
            List<T> values,
            Comparator<? super T> comparator,
            int maximum
    ) {
        int n = values.size();
        int k = Math.min(maximum, n);

        if (k <= 0) {
            values.clear();
            return;
        }

        if (n == 1) {
            return;
        }

        // 小规模数据：避免 PriorityQueue 和 Candidate 分配
        // k 接近 n：完整排序可能比维护大堆更便宜
        if (n <= 64 || (long) k * 2 >= n) {
            values.sort(comparator);

            if (k < n) {
                values.subList(k, n).clear();
            }
            return;
        }

        // k 明显小于 n，使用稳定 Top-K
        List<Candidate<T>> result = getCandidates(values, comparator, k);

        values.clear();

        for (Candidate<T> candidate : result) {
            values.add(candidate.value());
        }
    }

    private static <T> @NotNull List<Candidate<T>> getCandidates(List<T> values, Comparator<? super T> comparator, int k) {
        Comparator<Candidate<T>> order = (a, b) -> {
            int result = comparator.compare(a.value(), b.value());
            return result != 0
                    ? result
                    : Integer.compare(a.order(), b.order());
        };

        PriorityQueue<Candidate<T>> heap =
                new PriorityQueue<>(k, order.reversed());

        int index = 0;

        for (T value : values) {
            Candidate<T> candidate = new Candidate<>(value, index++);

            if (heap.size() < k) {
                heap.offer(candidate);
            } else if (order.compare(candidate, heap.peek()) < 0) {
                heap.poll();
                heap.offer(candidate);
            }
        }

        List<Candidate<T>> result = new ArrayList<>(heap);
        result.sort(order);
        return result;
    }

    static Map<String,Long> diagnostics(){return Map.of("chlorideLimitCalls",calls,"chlorideLimitCandidates",candidates,"chlorideLimitSelected",selected,"chlorideLimitInclusiveNs",cpuNs);}
}
