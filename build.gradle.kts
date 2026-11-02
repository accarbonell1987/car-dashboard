// Sin plugins en la raíz a propósito: cada módulo declara los suyos.
// Si el plugin de Kotlin se cargara aquí y el de Android (AGP) solo en las apps, Kotlin no vería
// las clases de AGP. Y declarar AGP aquí obligaría a bajarlo aunque solo se compile core y server.
