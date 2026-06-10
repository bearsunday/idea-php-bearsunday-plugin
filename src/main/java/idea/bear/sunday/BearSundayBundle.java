package idea.bear.sunday;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

public final class BearSundayBundle extends DynamicBundle {
    @NonNls
    private static final String BUNDLE = "messages.BearSundayBundle";
    private static final BearSundayBundle INSTANCE = new BearSundayBundle();

    private BearSundayBundle() {
        super(BUNDLE);
    }

    public static @NotNull String message(
        @NotNull @PropertyKey(resourceBundle = BUNDLE) String key,
        Object @NotNull ... params
    ) {
        return INSTANCE.getMessage(key, params);
    }
}
