<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\Annotation\Embed;
use BEAR\Resource\Annotation\Link;
use BEAR\Resource\ResourceObject;

final class RelationDemo extends ResourceObject
{
    #[Embed(src: 'app://self/point-dto', rel: 'dto')]
    #[Link(href: 'page://self/index', rel: 'home')]
    public function onGet(): static
    {
        $this->body = ['demo' => 'relation'];

        return $this;
    }
}
