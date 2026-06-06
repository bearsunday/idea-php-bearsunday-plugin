package idea.bear.sunday.relation;

import com.intellij.util.io.DataExternalizer;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ResourceRelationExternalizer implements DataExternalizer<List<ResourceRelation>> {

    @Override
    public void save(DataOutput out, List<ResourceRelation> value) throws IOException {
        out.writeInt(value.size());
        for (ResourceRelation relation : value) {
            out.writeUTF(relation.kind());
            out.writeUTF(relation.rel());
            out.writeUTF(relation.sourceUri());
            out.writeUTF(relation.sourceFqn());
            out.writeUTF(relation.targetUri());
            out.writeUTF(relation.targetMethod());
            out.writeUTF(relation.rawTargetUri());
            out.writeUTF(relation.sourceFilePath());
            out.writeInt(relation.attributeTextOffset());
        }
    }

    @Override
    public List<ResourceRelation> read(DataInput in) throws IOException {
        int size = in.readInt();
        List<ResourceRelation> value = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            value.add(new ResourceRelation(
                in.readUTF(),
                in.readUTF(),
                in.readUTF(),
                in.readUTF(),
                in.readUTF(),
                in.readUTF(),
                in.readUTF(),
                in.readUTF(),
                in.readInt()
            ));
        }

        return value;
    }
}
