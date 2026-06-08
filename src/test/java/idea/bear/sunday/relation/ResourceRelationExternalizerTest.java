package idea.bear.sunday.relation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceRelationExternalizerTest {

    @Test
    void roundTrip() throws Exception {
        List<ResourceRelation> relations = List.of(
            new ResourceRelation(
                "Embed",
                "user",
                "app://self/dashboard",
                "\\MyVendor\\Todo\\Resource\\App\\Dashboard",
                "app://self/user",
                "onGet",
                "app://self/user{?id}",
                "src/Resource/App/Dashboard.php",
                42
            ),
            new ResourceRelation(
                "Link",
                "author",
                "page://self/post",
                "\\MyVendor\\Todo\\Resource\\Page\\Post",
                "app://self/user",
                "onPost",
                "/user{?author_id}",
                "src/Resource/Page/Post.php",
                128
            )
        );

        ResourceRelationExternalizer externalizer = new ResourceRelationExternalizer();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        externalizer.save(new DataOutputStream(bytes), relations);

        List<ResourceRelation> restored = externalizer.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(relations, restored);
    }
}
