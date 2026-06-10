<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\Annotation\Link;
use BEAR\Resource\ResourceObject;

/**
 * Demo target for BEAR.Resource PHPDoc annotation completion.
 */
final class DocblockAnnotationDemo extends ResourceObject
{
    /**
     * Place the caret in this annotation to try completion for annotation attributes and URI values.
     *
     * @Link(rel="profile", href="app://self/profile{?id}")
     */
    public function onGet(int $id = 1): static
    {
        $this->body = [
            'id' => $id,
            'title' => 'PHPDoc annotation completion',
        ];

        return $this;
    }
}
