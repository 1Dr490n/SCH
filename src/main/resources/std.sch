package std

func print!(#i8[], ...)

func println!(#i8[], ...)

func readln!(): i8[]

func len!(#T[]): u32

func type!(#C): u32

func streql(a: i8[], b: i8[]): bool {
	var lenA = len!(a)
	if(lenA != len!(b))
		return false
	for(var i: u32 = 0; i < lenA; i += 1) {
		if(a[i] != b[i])
			return false
	}
	return true
}