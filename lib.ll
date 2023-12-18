%Array = type {
	i32, ; pointers
	i32, ; size
	ptr, ; array
	ptr, ; T_destroy
	i1   ; is_constant
}

%Class = type {
	i32, ; pointers
	i32, ; type
	ptr  ; destructor
}

declare ptr @malloc(i64)
declare ptr @realloc(ptr, i64)
declare i8 @fgetc(ptr)
declare ptr @__acrt_iob_func(i32)
declare void @free(ptr)
declare void @printf(ptr, ...)
declare void @exit(i32)

define ptr @Array_constructor(i32 %size, i64 %T_size, ptr %T_destroy) {
	%wrapper = call ptr @malloc(i64 32)
	%size64 = zext i32 %size to i64
	%size64_T = mul i64 %size64, %T_size
	%array = call ptr @malloc(i64 %size64_T)

	store i32 0, ptr %wrapper

	%sizeptr = getelementptr %Array, ptr %wrapper, i32 0, i32 1
	store i32 %size, ptr %sizeptr

	%arrayptr = getelementptr %Array, ptr %wrapper, i32 0, i32 2
	store ptr %array, ptr %arrayptr

	%destoryptr = getelementptr %Array, ptr %wrapper, i32 0, i32 3
	store ptr %T_destroy, ptr %destoryptr

	%is_constantptr = getelementptr %Array, ptr %wrapper, i32 0, i32 4
	store i1 0, ptr %is_constantptr

	ret ptr %wrapper
}
define ptr @Array_constructor2(i32 %size, i64 %T_size, ptr %T_destroy, ptr %line) {
	%.0 = icmp sge i32 %size, 0
	br i1 %.0, label %ok, label %err
err:
	call void @printf(ptr @.str_e_array_size, ptr @.str_error, i32 %size)
	call void @printf(ptr %line)
	call void @exit(i32 1)
	unreachable
ok:
	%.1 = call ptr @Array_constructor(i32 %size, i64 %T_size, ptr %T_destroy)
	ret ptr %.1
}
define void @Array_destructor(ptr %this) {
	%.0 = getelementptr %Array, ptr %this, i32 0, i32 2
	%.1 = load ptr, ptr %.0

	%.2 = getelementptr %Array, ptr %this, i32 0, i32 1
	%len = load i32, ptr %.2

	%.3 = getelementptr %Array, ptr %this, i32 0, i32 3
	%destroy = load ptr, ptr %.3

	%.12 = getelementptr %Array, ptr %this, i32 0, i32 2
	%array = load ptr, ptr %.12

	%i = alloca i32
	store i32 0, ptr %i

	%destroys = icmp ne ptr %destroy, null
	br i1 %destroys, label %for, label %n
for:
	%.8 = load i32, ptr %i
	%.9 = icmp slt i32 %.8, %len
	br i1 %.9, label %y, label %n
y:
	%.10 = load i32, ptr %i
	%.14 = getelementptr ptr, ptr %array, i32 %.10

	call void @dec(ptr %.14, ptr %destroy)

	%.00 = load i32, ptr %i
	%.01 = add i32 %.00, 1
	store i32 %.01, ptr %i
	br label %for
n:
	%is_constantptr = getelementptr %Array, ptr %this, i32 0, i32 4
	%is_constant = load i1, ptr %is_constantptr

	br i1 %is_constant, label %e, label %free
free:
	call void @free(ptr %array)
	br label %e
e:
	call void @free(ptr %this)
	ret void
}
define ptr @Array_get(ptr %array, i32 %T_size, i32 %index, ptr %line) {
	%sizeptr = getelementptr %Array, ptr %array, i32 0, i32 1
	%size = load i32, ptr %sizeptr



	%isNegative = icmp slt i32 %index, 0
	%negative = add i32 %size, %index

	%selectedIndex = select i1 %isNegative, i32 %negative, i32 %index

	%inBounds = icmp ult i32 %selectedIndex, %size
	br i1 %inBounds, label %y, label %n
n:
	call void @printf(ptr @.str_e_bounds, ptr @.str_error, i32 %index, i32 %size)
	call void @printf(ptr %line)
	call void @exit(i32 1)
	unreachable
y:

	%sizedindex = mul i32 %selectedIndex, %T_size
	%arrptr = getelementptr %Array, ptr %array, i32 0, i32 2

	%arr = load ptr, ptr %arrptr
	%ptr = getelementptr [1 x i8], ptr %arr, i32 %sizedindex
	ret ptr %ptr
}

define ptr @String_constructor(i32 %size, ptr %string) {
	%wrapper = call ptr @malloc(i64 32)

	store i32 0, ptr %wrapper

	%sizeptr = getelementptr %Array, ptr %wrapper, i32 0, i32 1
	store i32 %size, ptr %sizeptr

	%arrayptr = getelementptr %Array, ptr %wrapper, i32 0, i32 2
	store ptr %string, ptr %arrayptr

	%destoryptr = getelementptr %Array, ptr %wrapper, i32 0, i32 3
	store ptr null, ptr %destoryptr

	%is_constantptr = getelementptr %Array, ptr %wrapper, i32 0, i32 4
	store i1 1, ptr %is_constantptr

	ret ptr %wrapper
}

define void @null_assert(ptr %obj, ptr %line) {
	%.0 = icmp eq ptr %obj, null
	br i1 %.0, label %y, label %n
y:
	call void @printf(ptr @.str_e_null, ptr @.str_error)
	call void @printf(ptr %line)
	call void @exit(i32 1)
	unreachable
n:
	ret void
}
define i1 @is_subclass(ptr %obj, i32 %type, ptr %inheritances) {
	%objtypeptr = getelementptr %Class, ptr %obj, i32 0, i32 1
	%objtype = load i32, ptr %objtypeptr
	%typeptr = alloca i32
	store i32 %objtype, ptr %typeptr
	br label %f
f:
	%t = load i32, ptr %typeptr
	%iseql = icmp eq i32 %t, %type
	br i1 %iseql, label %y, label %n
n:
	%isnotzero = icmp ne i32 %t, 0
	br i1 %isnotzero, label %nz, label %y
nz:
	%.2 = sub i32 %t, 1
	%.0 = getelementptr [1 x i32], ptr %inheritances, i32 %.2
	%.1 = load i32, ptr %.0
	store i32 %.1, ptr %typeptr
	br label %f
y:
	ret i1 %iseql
}
define void @is_subclass_assert(ptr %obj, i32 %type, ptr %inheritances, ptr %typenames, ptr %file, i32 %line) {
	%iseql = call i1 (ptr, i32) @is_subclass(ptr %obj, i32 %type)
	br i1 %iseql, label %y, label %n
n:
	%type64 = zext i32 %type to i64
	%type64m1 = sub i64 %type64, 1
	%.0 = getelementptr [1 x ptr], ptr %typenames, i64 %type64m1
	%.1 = load ptr, ptr %.0

	%objtypeptr = getelementptr %Class, ptr %obj, i32 0, i32 1
	%objtype = load i32, ptr %objtypeptr
	%type64.2 = zext i32 %objtype to i64
	%type64m1.2 = sub i64 %type64.2, 1
	%.2 = getelementptr [1 x ptr], ptr %typenames, i64 %type64m1.2
	%.3 = load ptr, ptr %.2

	call void @printf(ptr @.str_e_cannot_cast, ptr @.str_error, ptr %.3, ptr %.1)
	call void @exit(i32 1)
	unreachable
y:
	ret void
}

define ptr @read(ptr %file) {
	%str = alloca ptr
	%size = alloca i64
	%len = alloca i32
	store i64 16, ptr %size
	store i32 0, ptr %len
	%.0 = call ptr @malloc(i64 16)
	store ptr %.0, ptr %str

	br label %cond
cond:
	%ch = call i8 @fgetc(ptr %file)
	%iseof = icmp eq i8 %ch, -1
	%isnew = icmp eq i8 %ch, 10
	%.1 = or i1 %iseof, %isnew
	br i1 %.1, label %e, label %l
l:
	%.2 = load ptr, ptr %str
	%.3 = load i32, ptr %len
	%.4 = zext i32 %.3 to i64
	%strptr = getelementptr [1 x i8], ptr %.2, i64 %.4
	store i8 %ch, ptr %strptr
	%.5 = add i32 1, %.3
	store i32 %.5, ptr %len

	%.6 = zext i32 %.5 to i64
	%.7 = load i64, ptr %size
	%.8 = icmp eq i64 %.6, %.7
	br i1 %.8, label %realloc, label %cond
realloc:
	%.9 = add i64 %.7, 16
	store i64 %.9, ptr %size

	%.10 = call ptr @realloc(ptr %.2, i64 %.9)
	store ptr %.10, ptr %str

	br label %cond
e:

	%.11 = load ptr, ptr %str
	%.12 = load i32, ptr %len
	%.13 = zext i32 %.12 to i64
	%.14 = call ptr @realloc(ptr %.11, i64 %.13)

	%wrapper = call ptr @malloc(i64 32)

	store i32 0, ptr %wrapper

	%sizeptr = getelementptr %Array, ptr %wrapper, i32 0, i32 1
	store i32 %.12, ptr %sizeptr

	%arrayptr = getelementptr %Array, ptr %wrapper, i32 0, i32 2
	store ptr %.14, ptr %arrayptr

	%destoryptr = getelementptr %Array, ptr %wrapper, i32 0, i32 3
	store ptr null, ptr %destoryptr

	%is_constantptr = getelementptr %Array, ptr %wrapper, i32 0, i32 4
	store i1 0, ptr %is_constantptr

	ret ptr %wrapper
}

@.str_e_bounds = constant [44 x i8] c"%sIndex %d out of bounds for array size %d\0a\00"
@.str_error = constant [25 x i8] c"\1B[31mRuntime error:\1B[0m \00"
@.str_e_null = constant [18 x i8] c"%sObject is null\0a\00"
@.str_e_cannot_cast = constant [28 x i8] c"%sCannot cast '%s' to '%s'\0a\00"
@.str_e_array_size = constant [57 x i8] c"%sArray size %u exceeds maximum 2147483647 (0x7fffffff)\0a\00"

define void @inc(ptr %t) {
	%.4 = load ptr, ptr %t

	%.7 = icmp eq ptr %.4, null
	br i1 %.7, label %y, label %n
n:
	%.5 = load i32, ptr %.4
	%.6 = add i32 %.5, 1
	store i32 %.6, ptr %.4
	br label %y
y:
	ret void
}
define void @inc_d(ptr %t) {
	%.7 = icmp eq ptr %t, null
	br i1 %.7, label %y, label %n
n:
	%.5 = load i32, ptr %t
	%.6 = add i32 %.5, 1
	store i32 %.6, ptr %t
	br label %y
y:
	ret void
}
@.strDestroyed = constant [11 x i8] c"Destroyed\0a\00"
define void @dec(ptr %t, ptr %f) {
	%.4 = load ptr, ptr %t
	%.8 = icmp eq ptr %.4, null
	br i1 %.8, label %n, label %n0
n0:
	%.5 = load i32, ptr %.4
	%.6 = sub i32 %.5, 1
	store i32 %.6, ptr %.4

	%.7 = icmp eq i32 %.6, 0
	br i1 %.7, label %y, label %n
y:
	call void @printf(ptr @.strDestroyed)
	call void %f(ptr %.4)
	br label %n
n:
	ret void
}