package idea.bear.sunday.aop;

import com.intellij.util.io.DataExternalizer;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class InterceptorBindingExternalizer implements DataExternalizer<List<String>> {

    @Override
    public void save(DataOutput out, List<String> value) throws IOException {
        out.writeInt(value.size());
        for (String fqn : value) {
            out.writeUTF(fqn);
        }
    }

    @Override
    public List<String> read(DataInput in) throws IOException {
        int size = in.readInt();
        List<String> value = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            value.add(in.readUTF());
        }
        return value;
    }
}
