<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Module;

use MyVendor\MyProject\Annotation\Audited;
use MyVendor\MyProject\Interceptor\AuditInterceptor;
use Ray\Di\AbstractModule;

final class AopDemoModule extends AbstractModule
{
    protected function configure(): void
    {
        $this->bindInterceptor(
            $this->matcher->any(),
            $this->matcher->annotatedWith(Audited::class),
            [AuditInterceptor::class],
        );
    }
}
