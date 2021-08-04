package inside.util;

import reactor.core.Exceptions;
import reactor.util.annotation.Nullable;

import java.nio.charset.*;
import java.util.function.Function;

public abstract class Strings{
    public static final int DEFAULT_LEVENSHTEIN_DST = 3;

    public static final Charset utf8 = StandardCharsets.UTF_8;

    private Strings(){

    }

    public static boolean isEmpty(@Nullable CharSequence cs){
        return cs == null || cs.length() == 0;
    }

    public static int parseInt(String s){
        return parseInt(s, Integer.MIN_VALUE);
    }

    public static int parseInt(String s, int defaultValue){
        try{
            return Integer.parseInt(s);
        }catch(Throwable t){
            Exceptions.throwIfJvmFatal(t);
            return defaultValue;
        }
    }

    public static long parseLong(String s){
        return parseLong(s, Long.MIN_VALUE);
    }

    public static long parseLong(String s, long defaultValue){
        try{
            return Long.parseLong(s);
        }catch(Throwable t){
            Exceptions.throwIfJvmFatal(t);
            return defaultValue;
        }
    }

    public static int levenshtein(CharSequence x, CharSequence y){
        int[][] dp = new int[x.length() + 1][y.length() + 1];

        for(int i = 0; i <= x.length(); i++){
            for(int j = 0; j <= y.length(); j++){
                if(i == 0){
                    dp[i][j] = j;
                }else if(j == 0){
                    dp[i][j] = i;
                }else{
                    dp[i][j] = Math.min(Math.min(dp[i - 1][j - 1] + (x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1),
                            dp[i - 1][j] + 1),
                            dp[i][j - 1] + 1);
                }
            }
        }

        return dp[x.length()][y.length()];
    }

    @Nullable
    public static <T extends CharSequence> T findClosest(Iterable<? extends T> all, CharSequence wrong){
        return findClosest(all, Function.identity(), wrong);
    }

    @Nullable
    public static <T> T findClosest(Iterable<? extends T> all, Function<T, ? extends CharSequence> comp, CharSequence wrong){
        return findClosest(all, comp, wrong, DEFAULT_LEVENSHTEIN_DST);
    }

    @Nullable
    public static <T> T findClosest(Iterable<? extends T> all, Function<T, ? extends CharSequence> comp, CharSequence wrong, int max){
        int min = 0;
        T closest = null;

        for(T t : all){
            int dst = levenshtein(comp.apply(t), wrong);
            if(dst < max && (closest == null || dst < min)){
                min = dst;
                closest = t;
            }
        }

        return closest;
    }
}
