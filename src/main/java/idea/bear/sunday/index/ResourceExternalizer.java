package idea.bear.sunday.index;

import com.intellij.util.io.DataExternalizer;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class ResourceExternalizer implements DataExternalizer<Resource> {

    @Override
    public void save(DataOutput out, Resource resource) throws IOException {
        out.writeUTF(resource.uri());
        out.writeUTF(resource.fqn());
    }

    @Override
    public Resource read(DataInput in) throws IOException {
        final String uri = in.readUTF();
        final String fqn = in.readUTF();
        return new Resource(uri, fqn);
    }

}
